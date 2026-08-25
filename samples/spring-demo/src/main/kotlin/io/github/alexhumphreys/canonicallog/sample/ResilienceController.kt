package io.github.alexhumphreys.canonicallog.sample

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * The resilience demo: an upstream call wrapped in a `Retry` around a `CircuitBreaker`.
 *
 * Nothing here mentions canonical-log's resilience fields — the starter attached the
 * contributor to the registry beans at startup. Call it and watch the canonical line:
 *
 * - first call for a path: the upstream 500s twice, the retry succeeds on the third attempt,
 *   and the line carries `retry_attempt_count=2` plus `retry_wait_duration_ms_total` — the
 *   answer to "why was this request slow?" that a duration alone can't give.
 * - keep calling fresh paths: the breaker opens, and lines flip to
 *   `resilience_rejected=true`, `circuit_breaker_rejected_count`, and
 *   `circuit_breaker_open_name=sample-upstream` — with **no** `http_client_request_count`,
 *   because the call never left the process. Shed, not failed.
 */
@RestController
class ResilienceController(
    private val http: OkHttpClient,
    private val upstream: MockWebServer,
    retryRegistry: RetryRegistry,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) {
    private val retry = retryRegistry.retry("sample-upstream")
    private val breaker = circuitBreakerRegistry.circuitBreaker("sample-upstream")

    @GetMapping("/posts/{id}/flaky")
    fun flaky(@PathVariable id: Long): Map<String, Any> {
        CanonicalLog.put("post_id", id)

        return try {
            retry.executeSupplier {
                breaker.executeSupplier {
                    val url = upstream.url("/flaky/$id").toString()
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        // The breaker only records what it's told is a failure; a 500 is one.
                        check(response.isSuccessful) { "upstream returned ${response.code}" }
                        mapOf<String, Any>("post_id" to id, "upstream" to "ok")
                    }
                }
            }
        } catch (e: CallNotPermittedException) {
            // Shedding is not the upstream failing — mark it as its own reason so the line's
            // error_reason agrees with resilience_rejected.
            CanonicalLog.markFailed("upstream_circuit_open")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upstream circuit open", e)
        } catch (e: IllegalStateException) {
            CanonicalLog.markFailed("upstream_unavailable")
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream unavailable", e)
        }
    }
}
