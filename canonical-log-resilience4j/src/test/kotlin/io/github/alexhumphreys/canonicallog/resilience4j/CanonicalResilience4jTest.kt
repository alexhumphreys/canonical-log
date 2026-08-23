package io.github.alexhumphreys.canonicallog.resilience4j

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.testCanonicalLogContext
import io.github.alexhumphreys.canonicallog.test.withBoundCanonicalContext
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Every case drives a *real* Resilience4j decoration — no hand-fired events — because the
 * thing worth pinning is which event actually reaches the caller's thread on each path.
 */
class CanonicalResilience4jTest : DescribeSpec({

    val fastRetry = RetryConfig.custom<Any>()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(10))
        .build()

    describe("retry") {
        it("counts retried attempts (not the initial call) and the backoff waited") {
            val registry = RetryRegistry.of(fastRetry)
            CanonicalResilience4j.register(registry)
            val retry = registry.retry("flaky")

            var calls = 0
            val ctx = testCanonicalLogContext()
            val result = withBoundCanonicalContext(ctx) {
                retry.executeSupplier {
                    calls++
                    if (calls < 3) error("boom") else "ok"
                }
            }

            result shouldBe "ok"
            calls shouldBe 3
            val snap = ctx.snapshot()
            // Three calls, two of them retries.
            snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
            (snap[CanonicalFields.RETRY_WAIT_DURATION_MS_TOTAL] as Long) shouldBeGreaterThanOrEqual 20L
            // A retry that eventually succeeded is not an error and is not a rejection.
            snap shouldNotContainKey CanonicalFields.RETRY_EXHAUSTED_COUNT
            snap shouldNotContainKey CanonicalFields.RESILIENCE_REJECTED
            snap shouldNotContainKey CanonicalFields.ERROR
        }

        it("records exhaustion when the retry gives up") {
            val registry = RetryRegistry.of(fastRetry)
            CanonicalResilience4j.register(registry)
            val retry = registry.retry("doomed")

            val ctx = testCanonicalLogContext()
            shouldThrow<IllegalStateException> {
                withBoundCanonicalContext(ctx) {
                    retry.executeSupplier { error("always") }
                }
            }

            val snap = ctx.snapshot()
            snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
            snap[CanonicalFields.RETRY_EXHAUSTED_COUNT] shouldBe 1L
        }

        it("contributes nothing when no work unit is bound") {
            val registry = RetryRegistry.of(fastRetry)
            CanonicalResilience4j.register(registry)
            val retry = registry.retry("unbound")

            var calls = 0
            // The point is that this doesn't throw — a contributor with nowhere to write
            // must not disturb the call it observes.
            retry.executeSupplier {
                calls++
                if (calls < 2) error("boom") else "ok"
            } shouldBe "ok"
        }

        it("attaches to instances the registry creates after registration") {
            val registry = RetryRegistry.of(fastRetry)
            CanonicalResilience4j.register(registry)
            // Created only now — the lazy-instance path Spring's @Retry annotation takes.
            val retry = registry.retry("born-late")

            var calls = 0
            val ctx = testCanonicalLogContext()
            withBoundCanonicalContext(ctx) {
                retry.executeSupplier {
                    calls++
                    if (calls < 2) error("boom") else "ok"
                }
            }

            ctx.snapshot()[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 1L
        }

        it("does not double-count when the same instance is registered twice") {
            val registry = RetryRegistry.of(fastRetry)
            val retry = registry.retry("shared")
            CanonicalResilience4j.register(registry)
            CanonicalResilience4j.register(registry)
            CanonicalResilience4j.attach(retry)

            var calls = 0
            val ctx = testCanonicalLogContext()
            withBoundCanonicalContext(ctx) {
                retry.executeSupplier {
                    calls++
                    if (calls < 2) error("boom") else "ok"
                }
            }

            ctx.snapshot()[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 1L
        }
    }

    describe("circuit breaker") {
        it("records the rejection, the breaker's name, and the shed flag") {
            val registry = CircuitBreakerRegistry.ofDefaults()
            CanonicalResilience4j.register(registry)
            val breaker = registry.circuitBreaker("payments-api")
            breaker.transitionToOpenState()

            var called = false
            val ctx = testCanonicalLogContext()
            shouldThrow<Exception> {
                withBoundCanonicalContext(ctx) {
                    breaker.executeSupplier { called = true }
                }
            }

            // The call never happened — that's what separates shedding from failure.
            called shouldBe false
            val snap = ctx.snapshot()
            snap[CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT] shouldBe 1L
            snap[CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME] shouldBe "payments-api"
            snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
            snap shouldNotContainKey CanonicalFields.CIRCUIT_BREAKER_FAILURE_COUNT
        }

        it("counts failures that went through a closed breaker without flagging a rejection") {
            val registry = CircuitBreakerRegistry.ofDefaults()
            CanonicalResilience4j.register(registry)
            val breaker = registry.circuitBreaker("closed-but-failing")

            val ctx = testCanonicalLogContext()
            shouldThrow<IllegalStateException> {
                withBoundCanonicalContext(ctx) {
                    breaker.executeSupplier { error("upstream down") }
                }
            }

            val snap = ctx.snapshot()
            snap[CanonicalFields.CIRCUIT_BREAKER_FAILURE_COUNT] shouldBe 1L
            snap shouldNotContainKey CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT
            snap shouldNotContainKey CanonicalFields.RESILIENCE_REJECTED
        }
    }

    describe("bulkhead") {
        it("records a rejection when no permit is available") {
            val registry = BulkheadRegistry.of(
                BulkheadConfig.custom()
                    .maxConcurrentCalls(1)
                    .maxWaitDuration(Duration.ZERO)
                    .build(),
            )
            CanonicalResilience4j.register(registry)
            val bulkhead: Bulkhead = registry.bulkhead("narrow")
            // Hold the only permit so the observed call is guaranteed to be refused.
            bulkhead.tryAcquirePermission() shouldBe true

            val ctx = testCanonicalLogContext()
            shouldThrow<Exception> {
                withBoundCanonicalContext(ctx) {
                    bulkhead.executeSupplier { "never runs" }
                }
            }

            val snap = ctx.snapshot()
            snap[CanonicalFields.BULKHEAD_REJECTED_COUNT] shouldBe 1L
            snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
        }
    }

    describe("rate limiter") {
        it("records a rejection when the limit is exhausted") {
            val registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                    .limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .timeoutDuration(Duration.ZERO)
                    .build(),
            )
            CanonicalResilience4j.register(registry)
            val limiter = registry.rateLimiter("throttled")
            limiter.acquirePermission() shouldBe true

            val ctx = testCanonicalLogContext()
            shouldThrow<Exception> {
                withBoundCanonicalContext(ctx) {
                    limiter.executeSupplier { "never runs" }
                }
            }

            val snap = ctx.snapshot()
            snap[CanonicalFields.RATE_LIMITER_REJECTED_COUNT] shouldBe 1L
            snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
        }
    }

    describe("time limiter") {
        it("records a timeout on the calling thread") {
            val registry = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(50)).build(),
            )
            CanonicalResilience4j.register(registry)
            val limiter: TimeLimiter = registry.timeLimiter("slow-upstream")
            val executor = Executors.newSingleThreadExecutor()

            val ctx = testCanonicalLogContext()
            try {
                shouldThrow<Exception> {
                    withBoundCanonicalContext(ctx) {
                        // The body runs on the executor; the timeout is observed here.
                        limiter.executeFutureSupplier {
                            executor.submit<String> {
                                TimeUnit.MILLISECONDS.sleep(500)
                                "too late"
                            }
                        }
                    }
                }
            } finally {
                executor.shutdownNow()
            }

            val snap = ctx.snapshot()
            snap[CanonicalFields.TIME_LIMITER_TIMEOUT_COUNT] shouldBe 1L
            snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
        }
    }

    describe("layered decorations") {
        it("separates retry attempts from the breaker rejection that ended them") {
            val retryRegistry = RetryRegistry.of(fastRetry)
            val breakerRegistry = CircuitBreakerRegistry.ofDefaults()
            CanonicalResilience4j.register(retryRegistry)
            CanonicalResilience4j.register(breakerRegistry)
            val retry = retryRegistry.retry("layered")
            val breaker = breakerRegistry.circuitBreaker("layered-upstream")
            breaker.transitionToOpenState()

            val ctx = testCanonicalLogContext()
            shouldThrow<Exception> {
                withBoundCanonicalContext(ctx) {
                    retry.executeSupplier {
                        breaker.executeSupplier { "never runs" }
                    }
                }
            }

            val snap = ctx.snapshot()
            // Retrying against an open breaker: every attempt is shed, and the line says so.
            snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
            snap[CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT] shouldBe 3L
            snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
            snap shouldContainKey CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME
        }
    }
})
