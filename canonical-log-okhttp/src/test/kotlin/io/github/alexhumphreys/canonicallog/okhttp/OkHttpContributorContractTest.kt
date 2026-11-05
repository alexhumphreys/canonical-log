package io.github.alexhumphreys.canonicallog.okhttp

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.contributorContract
import io.github.alexhumphreys.canonicallog.test.failingScenario
import io.github.alexhumphreys.canonicallog.test.scenario
import io.kotest.core.spec.style.DescribeSpec
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The shared contributor contract (todo 048) for [OkHttpCanonicalInterceptor]. Additive to
 * [OkHttpCanonicalInterceptorTest]: that suite pins the field semantics, this one pins the
 * no-throw / no-leak / type-convention contract every contributor runs.
 */
class OkHttpContributorContractTest : DescribeSpec({

    val server = MockWebServer()
    beforeSpec { server.start() }
    afterSpec { server.close() }

    val client = OkHttpClient.Builder().addInterceptor(OkHttpCanonicalInterceptor()).build()

    fun call(code: Int) {
        server.enqueue(MockResponse(code = code))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
    }

    fun enqueueAndAwait(request: Request.Builder) {
        server.enqueue(MockResponse(code = 200))
        val done = CountDownLatch(1)
        client.newCall(request.url(server.url("/")).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done.countDown()
            override fun onResponse(call: Call, response: Response) {
                response.close()
                done.countDown()
            }
        })
        check(done.await(10, TimeUnit.SECONDS)) { "enqueued call never completed" }
    }

    val callFields = mapOf(
        CanonicalFields.HTTP_CLIENT_REQUEST_COUNT to Long::class,
        CanonicalFields.HTTP_CLIENT_REQUEST_DURATION_MS_TOTAL to Long::class,
    )

    describe("OkHttpCanonicalInterceptor contributor contract") {
        contributorContract(
            name = "okhttp interceptor",
            operation = { call(200) },
            expectedFields = callFields,
            allowedFieldPrefixes = setOf("http_client_"),
            failure = failingScenario<IOException>(
                callFields + (CanonicalFields.HTTP_CLIENT_ERROR_COUNT to Long::class),
            ) {
                // Nothing listens on port 1: connect refused, no response.
                client.newCall(Request.Builder().url("http://127.0.0.1:1/").build()).execute().close()
            },
            adversarial = mapOf(
                "a 4xx response" to scenario(callFields + (CanonicalFields.HTTP_CLIENT_4XX_COUNT to Long::class)) {
                    call(404)
                },
                "a 5xx response" to scenario(callFields + (CanonicalFields.HTTP_CLIENT_5XX_COUNT to Long::class)) {
                    call(503)
                },
                "an enqueue() call tagged with the work unit" to scenario(callFields) {
                    enqueueAndAwait(Request.Builder().withCanonicalContext())
                },
                "an untagged enqueue() call (no binding on the dispatcher thread)" to scenario {
                    enqueueAndAwait(Request.Builder())
                },
                // The interceptor only catches IOException; anything else must pass through as-is.
                "a later interceptor throwing a non-IO exception" to failingScenario<IllegalStateException> {
                    OkHttpClient.Builder()
                        .addInterceptor(OkHttpCanonicalInterceptor())
                        .addInterceptor(Interceptor { error("downstream interceptor bug") })
                        .build()
                        .newCall(Request.Builder().url(server.url("/")).build())
                        .execute()
                        .close()
                },
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
