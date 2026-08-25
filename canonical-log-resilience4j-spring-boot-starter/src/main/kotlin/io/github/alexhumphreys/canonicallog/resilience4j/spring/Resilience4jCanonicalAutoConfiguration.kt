package io.github.alexhumphreys.canonicallog.resilience4j.spring

import io.github.alexhumphreys.canonicallog.resilience4j.CanonicalResilience4j
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Wires [CanonicalResilience4j] to whichever Resilience4j registries the context publishes,
 * so retries and rejections land on the canonical line with no application changes.
 *
 * Gated on [RetryRegistry] being on the classpath — in practice that means
 * `resilience4j-spring-boot3`, which publishes all six registries together — and on
 * `canonical-log.resilience4j.enabled` (active when absent).
 *
 * **Why a `SmartInitializingSingleton` over `@ConditionalOnBean` beans.** Registration is an
 * action, not a bean: it has to happen once, after the registries exist, whichever of them
 * exist. `ObjectProvider` resolves lazily at `afterSingletonsInstantiated`, which sidesteps
 * the auto-configuration ordering trap that makes `@ConditionalOnBean` on another
 * auto-configuration's beans unreliable. Registries the context doesn't publish are simply
 * skipped, and `CanonicalResilience4j` is idempotent per instance, so a context restart (or
 * a second registrar) can't double-count.
 */
@AutoConfiguration
@ConditionalOnClass(RetryRegistry::class)
@ConditionalOnProperty(
    name = ["canonical-log.resilience4j.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class Resilience4jCanonicalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public open fun canonicalResilience4jRegistrar(
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

/**
 * Attaches the canonical contributor to every Resilience4j registry in the context, once all
 * singletons exist. Public so an adopter can define their own bean of this type (the
 * auto-configuration backs off) or drive registration manually in a non-Boot context.
 */
public class CanonicalResilience4jRegistrar(
    private val retryRegistries: ObjectProvider<RetryRegistry>,
    private val circuitBreakerRegistries: ObjectProvider<CircuitBreakerRegistry>,
    private val bulkheadRegistries: ObjectProvider<BulkheadRegistry>,
    private val threadPoolBulkheadRegistries: ObjectProvider<ThreadPoolBulkheadRegistry>,
    private val rateLimiterRegistries: ObjectProvider<RateLimiterRegistry>,
    private val timeLimiterRegistries: ObjectProvider<TimeLimiterRegistry>,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        retryRegistries.forEach(CanonicalResilience4j::register)
        circuitBreakerRegistries.forEach(CanonicalResilience4j::register)
        bulkheadRegistries.forEach(CanonicalResilience4j::register)
        threadPoolBulkheadRegistries.forEach(CanonicalResilience4j::register)
        rateLimiterRegistries.forEach(CanonicalResilience4j::register)
        timeLimiterRegistries.forEach(CanonicalResilience4j::register)
    }
}
