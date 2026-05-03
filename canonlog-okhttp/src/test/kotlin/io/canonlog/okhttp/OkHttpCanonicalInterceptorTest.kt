package io.canonlog.okhttp

import io.canonlog.CanonicalLogContext
import io.canonlog.Outcome
import io.canonlog.WorkUnit
import io.canonlog.WorkUnitAdapter
import io.canonlog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.TimeUnit

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

private fun client(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(OkHttpCanonicalInterceptor())
    .build()

private fun get(c: OkHttpClient, url: String): Int =
    c.newCall(Request.Builder().url(url).build()).execute().use { it.code }

class OkHttpCanonicalInterceptorTest : DescribeSpec({

    lateinit var server: MockWebServer

    beforeEach {
        server = MockWebServer()
        server.start()
    }
    afterEach {
        server.close()
    }

    describe("OkHttpCanonicalInterceptor inside an active canonical context") {
        it("counts a 2xx call and accumulates duration") {
            server.enqueue(MockResponse(code = 200, body = "ok"))
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                get(client(), server.url("/").toString()) shouldBe 200
            }

            snap["http_client_request_count"] shouldBe 1L
            (snap["http_client_request_duration_ms_total"] as Long).shouldBeGreaterThanOrEqual(0L)
            snap.containsKey("http_client_4xx_count") shouldBe false
            snap.containsKey("http_client_5xx_count") shouldBe false
            snap.containsKey("http_client_error_count") shouldBe false
        }

        it("counts a redirect-following call as one request, not per network round-trip") {
            // 302 → 200. OkHttp follows redirects transparently inside chain.proceed(),
            // so an *application* interceptor (this contributor's intended slot) sees
            // one user call regardless of redirect depth. A regression that swapped to
            // per-network-roundtrip semantics would make this assertion fail.
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = Headers.headersOf("Location", "/target"),
                    body = "",
                ),
            )
            server.enqueue(MockResponse(code = 200, body = "ok"))
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                get(client(), server.url("/start").toString()) shouldBe 200
            }

            snap["http_client_request_count"] shouldBe 1L
            // The redirect's 302 is not bucketed (3xx is not in the convenience aggregates),
            // and the eventual 2xx is the response we observed — so neither 4xx nor 5xx fires.
            snap.containsKey("http_client_4xx_count") shouldBe false
            snap.containsKey("http_client_5xx_count") shouldBe false
        }

        it("counts a 404 as 4xx") {
            server.enqueue(MockResponse(code = 404))
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                get(client(), server.url("/missing").toString()) shouldBe 404
            }

            snap["http_client_request_count"] shouldBe 1L
            snap["http_client_4xx_count"] shouldBe 1L
            snap.containsKey("http_client_5xx_count") shouldBe false
        }

        it("counts a 500 as 5xx") {
            server.enqueue(MockResponse(code = 503))
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                get(client(), server.url("/").toString()) shouldBe 503
            }

            snap["http_client_request_count"] shouldBe 1L
            snap["http_client_5xx_count"] shouldBe 1L
            snap.containsKey("http_client_4xx_count") shouldBe false
        }

        it("counts an IOException as an error") {
            // Point at a closed socket on a routable but non-listening port.
            server.close()
            val deadUrl = "http://127.0.0.1:1/"
            var snap: Map<String, Any> = emptyMap()

            val ex = runCatching {
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    get(client(), deadUrl)
                }
            }.exceptionOrNull()

            (ex is IOException) shouldBe true
            snap["http_client_request_count"] shouldBe 1L
            snap["http_client_error_count"] shouldBe 1L
        }

        it("counts a read timeout as an error") {
            // SocketTimeoutException is an IOException, so the interceptor's catch handles
            // it. Pinning here documents that the timeout class of failures lands in
            // http_client_error_count, not in the 4xx/5xx buckets (no response was ever
            // received). The reverse — counting timeouts as 5xx — would be wrong: the
            // server didn't respond at all, it can't have indicated server failure.
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("late")
                    .headersDelay(2, TimeUnit.SECONDS)
                    .build(),
            )
            val slowClient = OkHttpClient.Builder()
                .addInterceptor(OkHttpCanonicalInterceptor())
                .readTimeout(200, TimeUnit.MILLISECONDS)
                .build()
            var snap: Map<String, Any> = emptyMap()

            val ex = runCatching {
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    get(slowClient, server.url("/slow").toString())
                }
            }.exceptionOrNull()

            (ex is SocketTimeoutException) shouldBe true
            snap["http_client_request_count"] shouldBe 1L
            snap["http_client_error_count"] shouldBe 1L
            snap.containsKey("http_client_5xx_count") shouldBe false
        }

        it("counts an SSL handshake failure as an error") {
            // Server presents a self-signed cert; the client uses an empty trust store
            // (trustNothing) so the handshake fails with SSLHandshakeException. Both
            // SSLHandshakeException and the broader SSLException family extend
            // IOException, so the interceptor's catch handles them; this test pins
            // that the SSL class of failures lands in http_client_error_count.
            val serverCert = HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .commonName("localhost")
                .build()
            val serverHandshake = HandshakeCertificates.Builder()
                .heldCertificate(serverCert)
                .build()
            // Client trusts nothing — i.e. doesn't trust the self-signed server cert.
            val clientHandshake = HandshakeCertificates.Builder().build()

            server.useHttps(serverHandshake.sslSocketFactory())
            server.enqueue(MockResponse(code = 200, body = "ok"))

            val httpsClient = OkHttpClient.Builder()
                .addInterceptor(OkHttpCanonicalInterceptor())
                .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            var snap: Map<String, Any> = emptyMap()

            val ex = runCatching {
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    get(httpsClient, server.url("/").toString())
                }
            }.exceptionOrNull()

            // The exact subclass varies by JDK/TLS implementation (SSLHandshakeException,
            // SSLPeerUnverifiedException, occasionally a plain IOException wrapping the
            // underlying cause). The contract under test is that whatever flavour OkHttp
            // surfaces, it extends IOException so the interceptor's catch block fires —
            // which is what produces http_client_error_count.
            (ex is IOException) shouldBe true
            snap["http_client_request_count"] shouldBe 1L
            snap["http_client_error_count"] shouldBe 1L
            snap.containsKey("http_client_5xx_count") shouldBe false
            snap.containsKey("http_client_4xx_count") shouldBe false
        }

        it("accumulates counters across multiple calls") {
            server.enqueue(MockResponse(code = 200))
            server.enqueue(MockResponse(code = 200))
            server.enqueue(MockResponse(code = 404))
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                val c = client()
                get(c, server.url("/a").toString())
                get(c, server.url("/b").toString())
                get(c, server.url("/c").toString())
            }

            snap["http_client_request_count"] shouldBe 3L
            snap["http_client_4xx_count"] shouldBe 1L
        }
    }

    describe("OkHttpCanonicalInterceptor outside an active canonical context") {
        it("is a no-op — request still succeeds, no exception") {
            server.enqueue(MockResponse(code = 200, body = "ok"))
            get(client(), server.url("/").toString()) shouldBe 200
        }
    }
})
