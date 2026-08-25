package io.github.alexhumphreys.canonicallog.resilience4j.spring

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

/** Stands in for what `resilience4j-spring-boot3` publishes. */
@Configuration
private open class RegistryConfig {
    @Bean
    open fun retryRegistry(): RetryRegistry = RetryRegistry.of(
        RetryConfig.custom<Any>().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build(),
    )

    @Bean
    open fun circuitBreakerRegistry(): CircuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults()
}

/** An adopter-supplied registrar: the auto-configuration must back off entirely. */
@Configuration
private open class CustomRegistrarConfig {
    @Bean("customRegistrar")
    open fun customRegistrar(
        retryRegistries: ObjectProvider<RetryRegistry>,
        circuitBreakerRegistries: ObjectProvider<CircuitBreakerRegistry>,
        bulkheadRegistries: ObjectProvider<BulkheadRegistry>,
        threadPoolBulkheadRegistries: ObjectProvider<ThreadPoolBulkheadRegistry>,
        rateLimiterRegistries: ObjectProvider<RateLimiterRegistry>,
        timeLimiterRegistries: ObjectProvider<TimeLimiterRegistry>,
    ): CanonicalResilience4jRegistrar = CanonicalResilience4jRegistrar(
        retryRegistries,
        circuitBreakerRegistries,
        bulkheadRegistries,
        threadPoolBulkheadRegistries,
        rateLimiterRegistries,
        timeLimiterRegistries,
    )
}

class Resilience4jCanonicalAutoConfigurationTest : DescribeSpec({
    val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(Resilience4jCanonicalAutoConfiguration::class.java),
        )

    describe("Resilience4jCanonicalAutoConfiguration") {

        it("registers the registrar by default") {
            runner.run { ctx ->
                ctx.getBeansOfType(CanonicalResilience4jRegistrar::class.java).size shouldBe 1
            }
        }

        it("starts with no registry beans present at all") {
            // ObjectProvider means "none published" is a no-op, not a failure — the app
            // may pull the starter in transitively before it uses Resilience4j.
            runner.run { ctx ->
                ctx.startupFailure shouldBe null
            }
        }

        it("opts out when canonical-log.resilience4j.enabled=false") {
            runner
                .withPropertyValues("canonical-log.resilience4j.enabled=false")
                .withUserConfiguration(RegistryConfig::class.java)
                .run { ctx ->
                    ctx.getBeansOfType(CanonicalResilience4jRegistrar::class.java).size shouldBe 0

                    // And with no registrar, a real retry contributes nothing.
                    val retry = ctx.getBean(RetryRegistry::class.java).retry("opted-out")
                    var snap: Map<String, Any> = emptyMap()
                    var calls = 0
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        retry.executeSupplier {
                            calls++
                            if (calls < 2) error("boom") else "ok"
                        }
                    }
                    snap shouldNotContainKey CanonicalFields.RETRY_ATTEMPT_COUNT
                }
        }

        it("attaches to the context's registries so a retry lands on the line") {
            runner.withUserConfiguration(RegistryConfig::class.java).run { ctx ->
                // Created after the context started — the lazily-created-instance path.
                val retry = ctx.getBean(RetryRegistry::class.java).retry("wired")
                var calls = 0
                var snap: Map<String, Any> = emptyMap()

                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    retry.executeSupplier {
                        calls++
                        if (calls < 3) error("boom") else "ok"
                    }
                }

                snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
            }
        }

        it("attaches to the circuit breaker registry too, flagging a shed call") {
            runner.withUserConfiguration(RegistryConfig::class.java).run { ctx ->
                val breaker = ctx.getBean(CircuitBreakerRegistry::class.java)
                    .circuitBreaker("wired-breaker")
                breaker.transitionToOpenState()

                var snap: Map<String, Any> = emptyMap()
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    runCatching { breaker.executeSupplier { "never runs" } }
                }

                snap[CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT] shouldBe 1L
                snap[CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME] shouldBe "wired-breaker"
                snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
            }
        }

        it("backs off when the adopter supplies their own registrar") {
            runner
                .withUserConfiguration(RegistryConfig::class.java, CustomRegistrarConfig::class.java)
                .run { ctx ->
                    ctx.getBeansOfType(CanonicalResilience4jRegistrar::class.java).keys shouldBe
                        setOf("customRegistrar")
                }
        }
    }
})
