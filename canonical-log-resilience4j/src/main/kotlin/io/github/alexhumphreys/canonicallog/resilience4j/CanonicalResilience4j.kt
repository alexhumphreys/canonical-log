package io.github.alexhumphreys.canonicallog.resilience4j

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.core.Registry
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import java.util.Collections
import java.util.WeakHashMap

/**
 * Contributes Resilience4j retry and rejection fields to the active canonical work unit.
 *
 * **What it answers.** A work unit that took 3.2s and succeeded looks identical on the
 * canonical line whether it was slow once or fast three times behind a `Retry`; a unit an
 * open circuit breaker refused looks like any other `error=true`. This contributor makes
 * both visible on the line that already describes the request:
 *
 * - [CanonicalFields.RETRY_ATTEMPT_COUNT] / [CanonicalFields.RETRY_EXHAUSTED_COUNT] /
 *   [CanonicalFields.RETRY_WAIT_DURATION_MS_TOTAL] — did this unit retry, how often, and how
 *   much of its wall-clock was backoff.
 * - [CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT] / [CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME] /
 *   [CanonicalFields.CIRCUIT_BREAKER_FAILURE_COUNT],
 *   [CanonicalFields.BULKHEAD_REJECTED_COUNT], [CanonicalFields.RATE_LIMITER_REJECTED_COUNT],
 *   [CanonicalFields.TIME_LIMITER_TIMEOUT_COUNT] — was the call shed, and by which layer.
 * - [CanonicalFields.RESILIENCE_REJECTED] — the one boolean that separates "we refused to
 *   call" from "the call failed", which today both collapse into `error=true`.
 *
 * **Not a metrics replacement.** Resilience4j's Micrometer binder answers the *global*
 * question ("how often is this breaker open?"). This answers the *per-request* one
 * ("did this request retry, or get shed?"). Run both; they don't overlap.
 *
 * **How it attaches: event publishers, no wrapping.** Register once at startup —
 * `CanonicalResilience4j.register(retryRegistry)` — and every decorated call contributes.
 * Nothing about the app's own code, decorations, or annotations changes. Registration covers
 * instances the registry creates *later* (Spring's `@Retry("name")` instances are created
 * lazily on first use) via the registry's `onEntryAdded` publisher, not just the ones present
 * at registration time.
 *
 * Registration is idempotent per instance: attaching twice to the same `Retry` — e.g. two
 * registries sharing an instance, or a Spring context restart — attaches consumers once, so
 * counts are never doubled. Tracked in a weak identity set, so instances stay collectable.
 *
 * **Threading.** Resilience4j publishes these events synchronously, on the thread that made
 * the decorated call — the thread the work unit is bound to — so [currentCanonicalContext]
 * resolves and the fields land with no propagation setup. Two caveats:
 *
 * - [ThreadPoolBulkhead] and [TimeLimiter] run the wrapped call on *another* thread. Their
 *   rejection/timeout events still fire on the caller (which is the case that matters here),
 *   but anything the wrapped body itself contributes needs the body submitted through
 *   `propagatingCanonicalContext()` like any other hand-off.
 * - Reactor/RxJava operator variants publish on whatever thread the operator runs on. Under
 *   `withCanonicalCoroutineContext` the coroutine bridge keeps the binding; under bare Reactor
 *   it does not, and the events silently no-op — the same gap as WebFlux support generally.
 *
 * **Never throws into the caller.** Every consumer body is guarded: with no work unit bound
 * the contributions are silent no-ops, and an unexpected throw is swallowed and recorded as
 * [CanonicalFields.CONTRIBUTOR_ERROR] rather than propagating into the app's live call.
 *
 * **Instance names.** The counters are deliberately name-free — a name belongs in a field
 * value, not a field name, or every new `Retry` mints new columns. The one exception is
 * [CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME], which records *which* breaker shed the call
 * (bounded: breaker names are configuration). Per-name breakdown of the counters is a
 * non-goal here; that's what the Micrometer tags are for.
 */
public object CanonicalResilience4j {

    /**
     * Instances already attached, held weakly by identity so a discarded registry's entries
     * stay collectable. Resilience4j's instances don't override `equals`, so `WeakHashMap`'s
     * lookup is identity in practice — which is exactly the intent.
     */
    private val attached: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    // --- Registry-level registration (the intended entry points) ---

    /** Attach to every [Retry] in [registry], now and as it creates more. */
    @JvmStatic
    public fun register(registry: RetryRegistry) {
        registry.attachAll(registry.allRetries, ::attach)
    }

    /** Attach to every [CircuitBreaker] in [registry], now and as it creates more. */
    @JvmStatic
    public fun register(registry: CircuitBreakerRegistry) {
        registry.attachAll(registry.allCircuitBreakers, ::attach)
    }

    /** Attach to every [Bulkhead] in [registry], now and as it creates more. */
    @JvmStatic
    public fun register(registry: BulkheadRegistry) {
        registry.attachAll(registry.allBulkheads, ::attach)
    }

    /** Attach to every [ThreadPoolBulkhead] in [registry], now and as it creates more. */
    @JvmStatic
    public fun register(registry: ThreadPoolBulkheadRegistry) {
        registry.attachAll(registry.allBulkheads, ::attach)
    }

    /** Attach to every [RateLimiter] in [registry], now and as it creates more. */
    @JvmStatic
    public fun register(registry: RateLimiterRegistry) {
        registry.attachAll(registry.allRateLimiters, ::attach)
    }

    /** Attach to every [TimeLimiter] in [registry], now and as it creates more. */
    @JvmStatic
    public fun register(registry: TimeLimiterRegistry) {
        registry.attachAll(registry.allTimeLimiters, ::attach)
    }

    // --- Instance-level registration (for hand-built instances with no registry) ---

    /** Attach to a single hand-built [Retry]. Idempotent. */
    @JvmStatic
    public fun attach(retry: Retry) {
        if (!claim(retry)) return
        retry.eventPublisher
            .onRetry { event ->
                guarded { ctx ->
                    // on_retry fires per *retried* attempt, so the initial call isn't counted:
                    // the field is "how many extra tries did this unit pay for".
                    ctx.increment(CanonicalFields.RETRY_ATTEMPT_COUNT)
                    ctx.increment(
                        CanonicalFields.RETRY_WAIT_DURATION_MS_TOTAL,
                        event.waitInterval.toMillis(),
                    )
                }
            }
            .onError {
                // Retry's on_error is "gave up and rethrew", not "an attempt failed" —
                // per-attempt failures surface as on_retry.
                guarded { ctx -> ctx.increment(CanonicalFields.RETRY_EXHAUSTED_COUNT) }
            }
    }

    /** Attach to a single hand-built [CircuitBreaker]. Idempotent. */
    @JvmStatic
    public fun attach(circuitBreaker: CircuitBreaker) {
        if (!claim(circuitBreaker)) return
        circuitBreaker.eventPublisher
            .onCallNotPermitted { event ->
                guarded { ctx ->
                    ctx.increment(CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT)
                    ctx.put(CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME, event.circuitBreakerName)
                    ctx.markRejected()
                }
            }
            .onError {
                guarded { ctx -> ctx.increment(CanonicalFields.CIRCUIT_BREAKER_FAILURE_COUNT) }
            }
    }

    /** Attach to a single hand-built [Bulkhead]. Idempotent. */
    @JvmStatic
    public fun attach(bulkhead: Bulkhead) {
        if (!claim(bulkhead)) return
        bulkhead.eventPublisher.onCallRejected {
            guarded { ctx ->
                ctx.increment(CanonicalFields.BULKHEAD_REJECTED_COUNT)
                ctx.markRejected()
            }
        }
    }

    /** Attach to a single hand-built [ThreadPoolBulkhead]. Idempotent. */
    @JvmStatic
    public fun attach(bulkhead: ThreadPoolBulkhead) {
        if (!claim(bulkhead)) return
        bulkhead.eventPublisher.onCallRejected {
            guarded { ctx ->
                ctx.increment(CanonicalFields.BULKHEAD_REJECTED_COUNT)
                ctx.markRejected()
            }
        }
    }

    /** Attach to a single hand-built [RateLimiter]. Idempotent. */
    @JvmStatic
    public fun attach(rateLimiter: RateLimiter) {
        if (!claim(rateLimiter)) return
        // RateLimiter's "failure" event is "no permission acquired" — a rejection, not an
        // error in the wrapped call.
        rateLimiter.eventPublisher.onFailure {
            guarded { ctx ->
                ctx.increment(CanonicalFields.RATE_LIMITER_REJECTED_COUNT)
                ctx.markRejected()
            }
        }
    }

    /** Attach to a single hand-built [TimeLimiter]. Idempotent. */
    @JvmStatic
    public fun attach(timeLimiter: TimeLimiter) {
        if (!claim(timeLimiter)) return
        timeLimiter.eventPublisher.onTimeout {
            guarded { ctx ->
                ctx.increment(CanonicalFields.TIME_LIMITER_TIMEOUT_COUNT)
                ctx.markRejected()
            }
        }
    }

    // --- Internals ---

    private fun <E : Any> Registry<E, *>.attachAll(existing: Set<E>, attach: (E) -> Unit) {
        // Late-created instances matter more than the ones present now: Spring's annotation
        // support (and any `registry.retry("name")` call) creates instances lazily on first
        // use, long after startup wiring runs.
        eventPublisher.onEntryAdded { event -> attach(event.addedEntry) }
        existing.forEach(attach)
    }

    /** `true` if this call is the one that attaches [instance]; `false` if already attached. */
    private fun claim(instance: Any): Boolean = attached.add(instance)

    /** Every rejection also raises the single "shed, not served" flag. */
    private fun CanonicalLogContext.markRejected() {
        put(CanonicalFields.RESILIENCE_REJECTED, true)
    }

    /**
     * Run [body] against the bound work unit, or do nothing if none is bound. Guarded because
     * this runs inside the app's live call path — a throw here would replace the real
     * exception (or fail a working call) with a telemetry bug.
     */
    private inline fun guarded(body: (CanonicalLogContext) -> Unit) {
        val ctx = currentCanonicalContext() ?: return
        try {
            body(ctx)
        } catch (e: Exception) {
            ctx.put(CanonicalFields.CONTRIBUTOR_ERROR, true)
            ctx.put(CanonicalFields.CONTRIBUTOR_ERROR_CLASS, e::class.qualifiedName ?: "unknown")
        }
    }
}
