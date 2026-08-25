package io.github.alexhumphreys.canonicallog.springretry

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener

/**
 * Contributes retry fields for **classic Spring Retry** (`org.springframework.retry`) — the
 * `@Retryable` / `RetryTemplate` library — to the active canonical work unit.
 *
 * Registering it as a bean is all that's needed: `@EnableRetry`'s `RetryConfiguration` collects
 * every `RetryListener` bean in the context and applies it to all `@Retryable` methods, so no
 * annotation, template, or call site changes. For a hand-built `RetryTemplate`, pass it to
 * `RetryTemplate.registerListener(...)`.
 *
 * **Fields** — the same vocabulary `canonical-log-resilience4j` writes, deliberately: the
 * constants name the *concept*, so a query for `retry_attempt_count` works across an app that
 * uses either library (or migrates between them).
 * - [CanonicalFields.RETRY_ATTEMPT_COUNT] — retried attempts, excluding the initial call.
 * - [CanonicalFields.RETRY_EXHAUSTED_COUNT] — the operation gave up and rethrew.
 *
 * Both are written once per retry *operation*, from `close`, rather than per attempt: `close`
 * runs after the whole thing settles, where `RetryContext.retryCount` and the terminal
 * throwable together say exactly what happened. A first-attempt success writes nothing at all —
 * absent means "didn't retry", per the omit-when-false rule.
 *
 * **No `retry_wait_duration_ms_total`.** Classic Spring Retry doesn't expose backoff intervals
 * to listeners, and inferring them from wall-clock between callbacks would report a number
 * that isn't the backoff. An honest gap beats a plausible-looking wrong field; the Resilience4j
 * contributor fills that field because its events actually carry the interval.
 *
 * **Recovery is not exhaustion.** If a `@Recover` method handles the final failure, the
 * operation still ends in `close` with the throwable, so `retry_exhausted_count` records that
 * the retries were used up — which is the operationally interesting fact — even though the
 * caller saw a successful return.
 *
 * Threading: Spring Retry is synchronous, so callbacks run on the thread the work unit is bound
 * to. Telemetry never breaks the call: with no work unit bound this is a silent no-op, and an
 * unexpected throw is recorded as [CanonicalFields.CONTRIBUTOR_ERROR] rather than propagating.
 */
public class CanonicalSpringRetryListener : RetryListener {

    override fun <T : Any?, E : Throwable> close(
        context: RetryContext,
        callback: RetryCallback<T, E>,
        throwable: Throwable?,
    ) {
        val ctx = currentCanonicalContext() ?: return
        guarded(ctx) {
            // retryCount is "failed attempts", incremented on every registered throwable —
            // including the last one when the operation gives up. Retried attempts are the
            // failures that were actually followed by another try, so a terminal failure
            // doesn't count: fail→fail→success is 2 retries, fail→fail→fail is also 2.
            val failedAttempts = context.retryCount.toLong()
            val exhausted = throwable != null
            val retriedAttempts = if (exhausted) failedAttempts - 1 else failedAttempts
            if (retriedAttempts > 0) {
                ctx.increment(CanonicalFields.RETRY_ATTEMPT_COUNT, retriedAttempts)
            }
            if (exhausted) {
                ctx.increment(CanonicalFields.RETRY_EXHAUSTED_COUNT)
            }
        }
    }
}

/**
 * Shared swallow guard: a contributor sits inside the app's live call path, so a throw here
 * would replace the real exception (or fail a working call) with a telemetry bug.
 */
internal inline fun guarded(ctx: CanonicalLogContext, body: () -> Unit) {
    try {
        body()
    } catch (e: Exception) {
        ctx.put(CanonicalFields.CONTRIBUTOR_ERROR, true)
        ctx.put(CanonicalFields.CONTRIBUTOR_ERROR_CLASS, e::class.qualifiedName ?: "unknown")
    }
}
