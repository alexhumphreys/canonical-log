package io.github.alexhumphreys.canonicallog.resilience4j

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.contributorContract
import io.github.alexhumphreys.canonicallog.test.failingScenario
import io.github.alexhumphreys.canonicallog.test.scenario
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.core.spec.style.DescribeSpec
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The shared contributor contract (todo 048) for [CanonicalResilience4j]. Every operation builds
 * a fresh registry and decoration so it is repeatable across the contract's cases; additive to
 * [CanonicalResilience4jTest], which pins the per-layer field semantics.
 */
class Resilience4jContributorContractTest : DescribeSpec({

    val fastRetry = RetryConfig.custom<Any>()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(1))
        .build()

    fun retry(name: String) = RetryRegistry.of(fastRetry).also(CanonicalResilience4j::register).retry(name)

    fun breaker(name: String) =
        CircuitBreakerRegistry.ofDefaults().also(CanonicalResilience4j::register).circuitBreaker(name)

    val retriedFields = mapOf(
        CanonicalFields.RETRY_ATTEMPT_COUNT to Long::class,
        CanonicalFields.RETRY_WAIT_DURATION_MS_TOTAL to Long::class,
    )
    val rejected = CanonicalFields.RESILIENCE_REJECTED to Boolean::class

    describe("CanonicalResilience4j contributor contract") {
        contributorContract(
            name = "resilience4j contributor",
            // A retry that succeeds on its third call: two retried attempts, some backoff.
            operation = {
                var calls = 0
                retry("flaky").executeSupplier {
                    calls++
                    if (calls < 3) error("boom") else "ok"
                }
            },
            expectedFields = retriedFields,
            allowedFieldPrefixes = setOf(
                "retry_",
                "circuit_breaker_",
                "bulkhead_",
                "rate_limiter_",
                "time_limiter_",
                "resilience_",
            ),
            failure = failingScenario<IllegalStateException>(
                retriedFields + (CanonicalFields.RETRY_EXHAUSTED_COUNT to Long::class),
            ) {
                retry("doomed").executeSupplier { error("always") }
            },
            adversarial = mapOf(
                "an open circuit breaker" to failingScenario<CallNotPermittedException>(
                    mapOf(
                        CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT to Long::class,
                        CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME to String::class,
                        rejected,
                    ),
                ) {
                    breaker("payments-api").apply { transitionToOpenState() }.executeSupplier { "never runs" }
                },
                "a closed breaker recording a failure" to failingScenario<IllegalStateException>(
                    mapOf(CanonicalFields.CIRCUIT_BREAKER_FAILURE_COUNT to Long::class),
                ) {
                    breaker("closed-but-failing").executeSupplier { error("upstream down") }
                },
                "a full bulkhead" to failingScenario<BulkheadFullException>(
                    mapOf(CanonicalFields.BULKHEAD_REJECTED_COUNT to Long::class, rejected),
                ) {
                    val config = BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()
                    val bulkhead = BulkheadRegistry.of(config).also(CanonicalResilience4j::register).bulkhead("narrow")
                    check(bulkhead.tryAcquirePermission()) // hold the only permit
                    bulkhead.executeSupplier { "never runs" }
                },
                "an exhausted rate limiter" to failingScenario<RequestNotPermitted>(
                    mapOf(CanonicalFields.RATE_LIMITER_REJECTED_COUNT to Long::class, rejected),
                ) {
                    val config = RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()
                    val limiter = RateLimiterRegistry.of(config).also(CanonicalResilience4j::register)
                        .rateLimiter("throttled")
                    check(limiter.acquirePermission())
                    limiter.executeSupplier { "never runs" }
                },
                "a time limiter firing" to failingScenario<TimeoutException>(
                    mapOf(CanonicalFields.TIME_LIMITER_TIMEOUT_COUNT to Long::class, rejected),
                ) {
                    val limiter = TimeLimiterRegistry.of(
                        TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(20)).build(),
                    ).also(CanonicalResilience4j::register).timeLimiter("slow-upstream")
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        limiter.executeFutureSupplier {
                            executor.submit<String> {
                                TimeUnit.MILLISECONDS.sleep(500)
                                "too late"
                            }
                        }
                    } finally {
                        executor.shutdownNow()
                    }
                },
                "a retry layered over an open breaker" to failingScenario<CallNotPermittedException>(
                    retriedFields + mapOf(
                        CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT to Long::class,
                        rejected,
                    ),
                ) {
                    val upstream = breaker("layered-upstream").apply { transitionToOpenState() }
                    retry("layered").executeSupplier { upstream.executeSupplier { "never runs" } }
                },
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
