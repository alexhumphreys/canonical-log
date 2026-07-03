package io.github.alexhumphreys.canonicallog.okhttp

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
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
 *
 * **Resolving the work unit: thread binding, or a request tag for async.** The
 * interceptor finds the active [CanonicalLogContext] two ways, tag first:
 * - a context attached to the request via [withCanonicalContext] (`request.tag`), or
 * - failing that, the context bound to the calling thread ([currentCanonicalContext]).
 *
 * `Call.execute()` runs the interceptor on the caller's thread, where the work unit is
 * already bound — so synchronous calls need nothing extra. `Call.enqueue()` runs the
 * interceptor on OkHttp's dispatcher threads, where no work unit is bound, so the
 * thread-binding path resolves to `null` and every field would be silently dropped.
 * The request tag crosses that hop: tag the request at build time (on the caller's
 * thread, where the context *is* bound) with
 * `Request.Builder().withCanonicalContext()` and the queued call contributes to the
 * originating work unit. An untagged `enqueue()` remains a no-op — the tag is opt-in.
 *
 * Note that wrapping the dispatcher's executor with `propagatingCanonicalContext()`
 * does NOT fix `enqueue()`: queued calls are promoted onto the executor from OkHttp's
 * own threads, so capture-at-submit grabs the wrong (empty) context. The tag is the
 * supported mechanism. (Coroutine callers can instead stay on `execute()` over a
 * dispatcher bridged via `withCanonicalCoroutineContext`, as the sample app does.)
 */
public class OkHttpCanonicalInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Tag wins over the thread binding: it's the only channel that survives the hop
        // to OkHttp's dispatcher threads on the enqueue() path. Resolved once — a
        // synchronous call doesn't change threads mid-flight, so this holds for both arms.
        val ctx = request.tag(CanonicalLogContext::class.java) ?: currentCanonicalContext()
        val startNs = System.nanoTime()

        try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000

            ctx?.increment(CanonicalFields.HTTP_CLIENT_REQUEST_COUNT)
            ctx?.increment(CanonicalFields.HTTP_CLIENT_REQUEST_DURATION_MS_TOTAL, durationMs)
            when {
                response.code >= 500 -> ctx?.increment(CanonicalFields.HTTP_CLIENT_5XX_COUNT)
                response.code >= 400 -> ctx?.increment(CanonicalFields.HTTP_CLIENT_4XX_COUNT)
            }

            return response
        } catch (e: IOException) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            ctx?.increment(CanonicalFields.HTTP_CLIENT_REQUEST_COUNT)
            ctx?.increment(CanonicalFields.HTTP_CLIENT_REQUEST_DURATION_MS_TOTAL, durationMs)
            ctx?.increment(CanonicalFields.HTTP_CLIENT_ERROR_COUNT)
            throw e
        }
    }
}
