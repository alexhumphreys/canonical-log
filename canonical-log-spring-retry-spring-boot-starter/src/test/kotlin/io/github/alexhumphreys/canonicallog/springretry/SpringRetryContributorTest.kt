package io.github.alexhumphreys.canonicallog.springretry

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.EnableRetry
import java.time.Instant
import org.springframework.resilience.annotation.Retryable as BuiltInRetryable
import org.springframework.retry.annotation.Retryable as ClassicRetryable

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

/**
 * Fails its first [failures] calls, then succeeds. [failures] is mutable so each test can set
 * the shape it needs — overriding the bean definition instead would collide with the
 * `@EnableRetry`/`@EnableResilientMethods` configurations that own it.
 */
private open class Flaky {
    var failures: Int = 0
    var calls: Int = 0

    open fun call(): String {
        calls++
        if (calls <= failures) error("boom $calls")
        return "ok"
    }
}

private open class ClassicService(private val flaky: Flaky) {
    @ClassicRetryable(maxAttempts = 3, backoff = Backoff(delay = 1))
    open fun call(): String = flaky.call()
}

@Configuration
@EnableRetry
private open class ClassicConfig {
    @Bean
    open fun flaky(): Flaky = Flaky()

    @Bean
    open fun service(flaky: Flaky): ClassicService = ClassicService(flaky)
}

private open class BuiltInService(private val flaky: Flaky) {
    @BuiltInRetryable(maxRetries = 1, delay = 1)
    open fun call(): String = flaky.call()
}

@Configuration
@EnableResilientMethods
private open class BuiltInConfig {
    @Bean
    open fun flaky(): Flaky = Flaky()

    @Bean
    open fun service(flaky: Flaky): BuiltInService = BuiltInService(flaky)
}

/**
 * Drives *real* `@Retryable` proxies of both retry implementations — the point is to pin what
 * each library's callback sequence actually reports, not what its javadoc suggests.
 */
class SpringRetryContributorTest : DescribeSpec({

    val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SpringRetryCanonicalAutoConfiguration::class.java),
        )

    describe("classic spring-retry (@EnableRetry)") {

        it("counts retried attempts on a retry that eventually succeeds") {
            runner.withUserConfiguration(ClassicConfig::class.java).run { ctx ->
                ctx.getBean(Flaky::class.java).failures = 2
                val service = ctx.getBean(ClassicService::class.java)
                var snap: Map<String, Any> = emptyMap()

                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    service.call() shouldBe "ok"
                }

                // Three attempts, two of them retries.
                ctx.getBean(Flaky::class.java).calls shouldBe 3
                snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
                snap shouldNotContainKey CanonicalFields.RETRY_EXHAUSTED_COUNT
            }
        }

        it("records exhaustion when the retries run out") {
            runner.withUserConfiguration(ClassicConfig::class.java).run { ctx ->
                    ctx.getBean(Flaky::class.java).failures = 99
                    val service = ctx.getBean(ClassicService::class.java)
                    var snap: Map<String, Any> = emptyMap()

                    shouldThrow<IllegalStateException> {
                        withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                            service.call()
                        }
                    }

                    snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
                    snap[CanonicalFields.RETRY_EXHAUSTED_COUNT] shouldBe 1L
                }
        }

        it("writes nothing when the first attempt succeeds") {
            runner.withUserConfiguration(ClassicConfig::class.java).run { ctx ->
                    var snap: Map<String, Any> = emptyMap()
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        ctx.getBean(ClassicService::class.java).call() shouldBe "ok"
                    }

                    // Absent means "didn't retry" — no zero-valued noise on the line.
                    snap shouldNotContainKey CanonicalFields.RETRY_ATTEMPT_COUNT
                    snap shouldNotContainKey CanonicalFields.RETRY_EXHAUSTED_COUNT
                }
        }

        it("contributes nothing, and breaks nothing, with no work unit bound") {
            runner.withUserConfiguration(ClassicConfig::class.java).run { ctx ->
                ctx.getBean(ClassicService::class.java).call() shouldBe "ok"
            }
        }
    }

    describe("Spring Framework built-in retry (@EnableResilientMethods)") {

        it("counts the retried attempt on a retry that eventually succeeds") {
            runner.withUserConfiguration(BuiltInConfig::class.java).run { ctx ->
                    ctx.getBean(Flaky::class.java).failures = 1
                    var snap: Map<String, Any> = emptyMap()
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        ctx.getBean(BuiltInService::class.java).call() shouldBe "ok"
                    }

                    snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 1L
                    snap shouldNotContainKey CanonicalFields.RETRY_EXHAUSTED_COUNT
                }
        }

        it("records exhaustion when the retries run out") {
            runner.withUserConfiguration(BuiltInConfig::class.java).run { ctx ->
                ctx.getBean(Flaky::class.java).failures = 99
                var snap: Map<String, Any> = emptyMap()

                shouldThrow<Exception> {
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        ctx.getBean(BuiltInService::class.java).call()
                    }
                }

                snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 1L
                snap[CanonicalFields.RETRY_EXHAUSTED_COUNT] shouldBe 1L
            }
        }

        it("sums correctly across several operations in one work unit") {
            // The aborted event's downward correction has to net out per operation, not just
            // for a single call — this is the case that would expose an off-by-one.
            runner.withUserConfiguration(BuiltInConfig::class.java).run { ctx ->
                val service = ctx.getBean(BuiltInService::class.java)
                val flaky = ctx.getBean(Flaky::class.java)
                var snap: Map<String, Any> = emptyMap()

                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    // One operation that retries once and succeeds...
                    flaky.failures = 1
                    flaky.calls = 0
                    service.call() shouldBe "ok"
                    // ...and one that retries once and then gives up.
                    flaky.failures = 99
                    flaky.calls = 0
                    runCatching { service.call() }
                }

                snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
                snap[CanonicalFields.RETRY_EXHAUSTED_COUNT] shouldBe 1L
            }
        }

        it("contributes nothing, and breaks nothing, with no work unit bound") {
            runner.withUserConfiguration(BuiltInConfig::class.java).run { ctx ->
                ctx.getBean(Flaky::class.java).failures = 1
                ctx.getBean(BuiltInService::class.java).call() shouldBe "ok"
            }
        }
    }

    describe("auto-configuration") {

        it("registers both listeners when both retry flavours are on the classpath") {
            runner.run { ctx ->
                ctx.getBeansOfType(CanonicalSpringRetryListener::class.java).size shouldBe 1
                ctx.getBeansOfType(CanonicalMethodRetryListener::class.java).size shouldBe 1
            }
        }

        it("opts out when canonical-log.spring-retry.enabled=false") {
            runner
                .withPropertyValues("canonical-log.spring-retry.enabled=false")
                .withUserConfiguration(ClassicConfig::class.java)
                .run { ctx ->
                    ctx.getBeansOfType(CanonicalSpringRetryListener::class.java).size shouldBe 0
                    ctx.getBeansOfType(CanonicalMethodRetryListener::class.java).size shouldBe 0

                    var snap: Map<String, Any> = emptyMap()
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        ctx.getBean(ClassicService::class.java).call()
                    }
                    snap shouldNotContainKey CanonicalFields.RETRY_ATTEMPT_COUNT
                }
        }

        it("backs off when the adopter supplies their own listener beans") {
            runner
                .withBean("myClassic", CanonicalSpringRetryListener::class.java)
                .withBean("myBuiltIn", CanonicalMethodRetryListener::class.java)
                .run { ctx ->
                    ctx.getBeansOfType(CanonicalSpringRetryListener::class.java).keys shouldBe
                        setOf("myClassic")
                    ctx.getBeansOfType(CanonicalMethodRetryListener::class.java).keys shouldBe
                        setOf("myBuiltIn")
                }
        }
    }
})
