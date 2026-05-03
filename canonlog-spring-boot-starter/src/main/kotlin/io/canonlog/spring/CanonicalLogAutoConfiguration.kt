package io.canonlog.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    name = ["canonlog.http.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class CanonicalLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public open fun canonicalLogFilter(): CanonicalLogFilter = CanonicalLogFilter()

    @Bean
    public open fun canonicalLogFilterRegistration(
        filter: CanonicalLogFilter,
    ): FilterRegistrationBean<CanonicalLogFilter> {
        val registration = FilterRegistrationBean(filter)
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }
}
