package io.github.alexhumphreys.canonicallog.springretry

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers whichever retry contributors the classpath supports.
 *
 * The two halves are independent and both may apply in the same app:
 * - [CanonicalMethodRetryListener] for Spring Framework 7's built-in `@Retryable`, available to
 *   every Boot 4 app (the event type lives in `spring-context`).
 * - [CanonicalSpringRetryListener] for classic `org.springframework.retry`, only when that
 *   library is on the classpath.
 *
 * Registering a listener bean is the whole integration: `@EnableRetry` collects `RetryListener`
 * beans, and application events reach any `ApplicationListener`. Nothing wraps or re-proxies
 * the adopter's beans.
 *
 * Opt out entirely with `canonical-log.spring-retry.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(
    name = ["canonical-log.spring-retry.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class SpringRetryCanonicalAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.resilience.retry.MethodRetryEvent::class)
    public open class BuiltInRetryConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public open fun canonicalMethodRetryListener(): CanonicalMethodRetryListener =
            CanonicalMethodRetryListener()
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.retry.RetryListener::class)
    public open class ClassicSpringRetryConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public open fun canonicalSpringRetryListener(): CanonicalSpringRetryListener =
            CanonicalSpringRetryListener()
    }
}
