package io.canonlog.sample

import io.canonlog.CanonicalLog
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

data class PostResponse(
    val id: Long,
    val title: String,
    val authorName: String,
    val commentCount: Int,
)

@RestController
class PostsController(
    private val jdbc: JdbcTemplate,
    private val http: OkHttpClient,
    private val upstream: MockWebServer,
) {

    @GetMapping("/posts/{id}/explode")
    fun explode(@PathVariable id: Long): Nothing {
        CanonicalLog.put("post_id", id)
        // No markFailed — let the exception flow uncaught so the adapter populates
        // error_class itself. This demonstrates the third outcome shape:
        // Outcome.Threw → adapter sets error=true, error_class=<qualifiedName>,
        // error_reason=exception (default, since no markFailed call).
        throw RuntimeException("simulated handler crash for post $id")
    }

    @GetMapping("/posts/{id}")
    fun getPost(@PathVariable id: Long): PostResponse {
        CanonicalLog.put("post_id", id)

        val title = jdbc.queryForList(
            "SELECT title FROM posts WHERE id = ?",
            String::class.java,
            id,
        ).firstOrNull() ?: run {
            CanonicalLog.markFailed("post_not_found", "post_id" to id)
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

        // Touch a second table to demonstrate db_query_count / db_execution_count > 1
        val tagCount = jdbc.queryForObject(
            "SELECT count(*) FROM post_tags WHERE post_id = ?",
            Int::class.java,
            id,
        ) ?: 0
        CanonicalLog.put("tag_count", tagCount)

        val commentCount = fetchJson("/comments/$id").substringAfter(""""count":""").substringBefore("}").toInt()
        val authorName = fetchJson("/author/$id").substringAfter(""""name":"""").substringBefore("\"")

        CanonicalLog.put("comment_count", commentCount)
        CanonicalLog.put("cache_hit", false)

        return PostResponse(id, title, authorName, commentCount)
    }

    private fun fetchJson(path: String): String {
        val url = upstream.url(path).toString()
        return http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            resp.body.string()
        }
    }
}
