package io.canonlog.sample

import io.canonlog.CanonicalLog
import io.canonlog.withCanonicalCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class SuspendPostsController(
    private val jdbc: JdbcTemplate,
    private val http: OkHttpClient,
    private val upstream: MockWebServer,
) {

    @GetMapping("/suspend/posts/{id}")
    suspend fun getPost(@PathVariable id: Long): PostResponse = withCanonicalCoroutineContext {
        CanonicalLog.put("post_id", id)

        val title = withContext(Dispatchers.IO) {
            jdbc.queryForList(
                "SELECT title FROM posts WHERE id = ?",
                String::class.java,
                id,
            ).firstOrNull()
        } ?: run {
            CanonicalLog.markFailed("post_not_found", "post_id" to id)
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

        val tagCount = withContext(Dispatchers.IO) {
            jdbc.queryForObject(
                "SELECT count(*) FROM post_tags WHERE post_id = ?",
                Int::class.java,
                id,
            ) ?: 0
        }
        CanonicalLog.put("tag_count", tagCount)

        val (commentJson, authorJson) = coroutineScope {
            val a = async(Dispatchers.IO) { fetchJson("/comments/$id") }
            val b = async(Dispatchers.IO) { fetchJson("/author/$id") }
            listOf(a, b).awaitAll()
        }.let { it[0] to it[1] }

        val commentCount = commentJson.substringAfter(""""count":""").substringBefore("}").toInt()
        val authorName = authorJson.substringAfter(""""name":"""").substringBefore("\"")

        CanonicalLog.put("comment_count", commentCount)
        CanonicalLog.put("cache_hit", false)

        PostResponse(id, title, authorName, commentCount)
    }

    private fun fetchJson(path: String): String {
        val url = upstream.url(path).toString()
        return http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            resp.body.string()
        }
    }
}
