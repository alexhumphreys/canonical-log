package io.github.alexhumphreys.canonicallog.springretry

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import org.springframework.context.ApplicationListener
import org.springframework.resilience.retry.MethodRetryEvent

/**
 * Contributes retry fields for **Spring Framework 7's built-in retry** — the
 * `org.springframework.resilience.annotation.@Retryable` / `@EnableResilientMethods` support
 * that ships in `spring-context`, distinct from the classic `org.springframework.retry`
 * library ([CanonicalSpringRetryListener] covers that one).
 *
 * The seam is an application event: the retry interceptor publishes a [MethodRetryEvent] for
 * every failed attempt, with `isRetryAborted()` marking the one where it gave up. Listening is
 * fully transparent — no AOP config, no wrapping, no changes to annotated methods.
 *
 * **Fields** (the same concept-named constants the Resilience4j contributor writes):
 * - [CanonicalFields.RETRY_ATTEMPT_COUNT] — failures that were followed by another attempt.
 *   The final, given-up failure is excluded, so this counts retried attempts, not attempts.
 * - [CanonicalFields.RETRY_EXHAUSTED_COUNT] — retries were used up.
 *
 * **Why the counter is corrected downwards.** The interceptor publishes one event per failed
 * attempt (`isRetryAborted == false`) and then a *separate* aborted event when the policy runs
 * out — so the last failure of a doomed operation has already been counted as a retry by the
 * time exhaustion is known. Each failure event counts optimistically and the aborted event
 * takes one back, which lands on the same totals as the Resilience4j contributor: a
 * fail→fail→success operation reports 2, and a fail→fail (maxRetries=1) operation reports 1
 * retry plus 1 exhaustion. Deriving it any other way would need per-operation correlation that
 * the event doesn't carry.
 *
 * `retry_wait_duration_ms_total` is not written — the event doesn't carry the backoff interval,
 * and a guessed number is worse than an absent one.
 *
 * **Threading caveat.** Spring publishes application events synchronously by default, on the
 * thread that made the call — the thread the work unit is bound to. An application that
 * replaces the context's `applicationEventMulticaster` with an asynchronous one moves these
 * events onto a task executor where no work unit is bound, and the contributions silently
 * no-op. That's rare and deliberate; it's the one wiring choice that disables this listener.
 *
 * Telemetry never breaks the call: with no work unit bound this is a silent no-op, and an
 * unexpected throw is recorded as [CanonicalFields.CONTRIBUTOR_ERROR].
 */
public class CanonicalMethodRetryListener : ApplicationListener<MethodRetryEvent> {

    override fun onApplicationEvent(event: MethodRetryEvent) {
        val ctx = currentCanonicalContext() ?: return
        guarded(ctx) {
            if (event.isRetryAborted) {
                ctx.increment(CanonicalFields.RETRY_EXHAUSTED_COUNT)
                // See the class KDoc: the aborted event follows a failure event that was
                // optimistically counted as a retry but turned out to be the last attempt.
                ctx.increment(CanonicalFields.RETRY_ATTEMPT_COUNT, -1L)
            } else {
                ctx.increment(CanonicalFields.RETRY_ATTEMPT_COUNT)
            }
        }
    }
}
