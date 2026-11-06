package io.github.alexhumphreys.canonicallog.springretry

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.contributorContract
import io.github.alexhumphreys.canonicallog.test.failingScenario
import io.github.alexhumphreys.canonicallog.test.scenario
import io.kotest.core.spec.style.DescribeSpec
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.resilience.annotation.Retryable
import org.springframework.retry.support.RetryTemplate

/** Fails the first [failures] calls of each operation, then succeeds. */
private fun flaky(failures: Int): () -> String {
    var calls = 0
    return {
        calls++
        if (calls <= failures) error("boom $calls")
        "ok"
    }
}

private open class ContractRetryService {
    @Retryable(maxRetries = 2, delay = 1)
    open fun call(op: () -> String): String = op()

    @Retryable(maxRetries = 0, delay = 1)
    open fun callOnce(op: () -> String): String = op()
}

@Configuration
@EnableResilientMethods
private open class ContractBuiltInConfig {
    @Bean
    open fun service(): ContractRetryService = ContractRetryService()
}

/**
 * The shared contributor contract (todo 048) for both retry listeners. Classic Spring Retry is
 * driven through a hand-built `RetryTemplate` with the listener registered (no context needed);
 * the Framework 7 built-in `@Retryable` through one real context with the auto-configuration, so
 * the `MethodRetryEvent`s are the interceptor's own. Additive to [SpringRetryContributorTest].
 */
class SpringRetryContributorContractTest : DescribeSpec({

    fun template(maxAttempts: Int = 3): RetryTemplate = RetryTemplate.builder()
        .maxAttempts(maxAttempts)
        .fixedBackoff(1)
        .withListener(CanonicalSpringRetryListener())
        .build()

    val retried = mapOf(CanonicalFields.RETRY_ATTEMPT_COUNT to Long::class)
    val exhausted = CanonicalFields.RETRY_EXHAUSTED_COUNT to Long::class

    describe("classic spring-retry listener contributor contract") {
        contributorContract(
            name = "classic spring-retry listener",
            operation = {
                val op = flaky(2)
                template().execute<String, RuntimeException> { op() }
            },
            expectedFields = retried,
            allowedFieldPrefixes = setOf("retry_"),
            failure = failingScenario<IllegalStateException>(retried + exhausted) {
                val op = flaky(99)
                template().execute<String, RuntimeException> { op() }
            },
            adversarial = mapOf(
                "a first-attempt success (writes nothing)" to scenario {
                    template().execute<String, RuntimeException> { "ok" }
                },
                "a single-attempt policy that fails" to failingScenario<IllegalStateException>(mapOf(exhausted)) {
                    val op = flaky(99)
                    template(maxAttempts = 1).execute<String, RuntimeException> { op() }
                },
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }

    val context = AnnotationConfigApplicationContext()
    beforeSpec {
        context.register(ContractBuiltInConfig::class.java, SpringRetryCanonicalAutoConfiguration::class.java)
        context.refresh()
    }
    afterSpec { context.close() }
    fun service(): ContractRetryService = context.getBean(ContractRetryService::class.java)

    describe("built-in @Retryable listener contributor contract") {
        contributorContract(
            name = "built-in retry listener",
            operation = { service().call(flaky(2)) },
            expectedFields = retried,
            allowedFieldPrefixes = setOf("retry_"),
            failure = failingScenario<IllegalStateException>(retried + exhausted) {
                service().call(flaky(99))
            },
            adversarial = mapOf(
                "a first-attempt success (writes nothing)" to scenario { service().call(flaky(0)) },
                "a no-retry policy that fails" to failingScenario<IllegalStateException>(mapOf(exhausted)) {
                    service().callOnce(flaky(99))
                },
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
