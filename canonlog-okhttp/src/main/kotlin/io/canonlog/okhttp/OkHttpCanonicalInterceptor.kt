package io.canonlog.okhttp

import io.canonlog.CanonicalLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp interceptor that contributes outbound-HTTP fields to the active canonical
 * work unit.
 *
 * **Register as an application interceptor.** Use `OkHttpClient.Builder.addInterceptor`,
 * NOT `addNetworkInterceptor`. The two run at different layers and answer different
 * questions:
 *
 * - Application (this contributor's intended slot): fires once per *user-issued* call.
 *   Redirects and connection-level retries are followed transparently inside
 *   `chain.proceed()`. `http_client_request_count` answers "how many outbound calls
 *   did this work unit make?".
 * - Network: fires once per *network round-trip*. A user call that follows two
 *   redirects fires three times. Wired up here it would inflate the counts in a
 *   way most operators don't expect.
 *
 * **Fields contributed:**
 * - `http_client_request_count` — one increment per user call (per `intercept`
 *   invocation). Connection-level retries OkHttp performs transparently inside
 *   `chain.proceed()` are NOT separate calls.
 * - `http_client_request_duration_ms_total` — wall-clock time spent in
 *   `chain.proceed()`, including any transparent connection retries and redirect
 *   follow-ups. This is why a small `request_count` can sometimes surface a
 *   surprisingly large duration.
 * - `http_client_4xx_count`, `http_client_5xx_count` — convenience buckets so
 *   operators can compute error rate at a glance from the canonical line. Other
 *   status classes (1xx / 2xx / 3xx) are not bucketed by design — these are the
 *   "errors-only" aggregates a canonical-line query typically wants.
 * - `http_client_error_count` — calls that failed *without* a response (the
 *   request never made it back: connect refused, DNS failure, timeout, SSL
 *   handshake failure, etc.). Distinct from `http_client_5xx_count`, which is
 *   "got a response and the server reported failure." A 4xx is "got a response
 *   and the server reported the request was bad." Operators who want overall
 *   failure rate compute `(error_count + 5xx_count) / request_count`; whether
 *   to also include 4xx is a judgement call (often yes for the calling service,
 *   no for the upstream service).
 */
public class OkHttpCanonicalInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNs = System.nanoTime()

        try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000

            CanonicalLog.increment("http_client_request_count")
            CanonicalLog.increment("http_client_request_duration_ms_total", durationMs)
            when {
                response.code >= 500 -> CanonicalLog.increment("http_client_5xx_count")
                response.code >= 400 -> CanonicalLog.increment("http_client_4xx_count")
            }

            return response
        } catch (e: IOException) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            CanonicalLog.increment("http_client_request_count")
            CanonicalLog.increment("http_client_request_duration_ms_total", durationMs)
            CanonicalLog.increment("http_client_error_count")
            throw e
        }
    }
}
