package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
    name = ["canonical-log.http.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(CanonicalLogHttpProperties::class)
public open class CanonicalLogAutoConfiguration {

    /**
     * User beans win over the defaults: a `WorkUnitAdapter<HttpExchange>` bean replaces
     * [HttpWorkUnitAdapter] (compose with it for extra uniform fields — see
     * [CanonicalLogFilter]), a [CanonicalLineWriter] bean replaces the logstash sink,
     * a [CanonicalLogSampler] bean replaces the emit-all default.
     *
     * Resolution uses [ObjectProvider], not `@ConditionalOnMissingBean` on default
     * beans: CoMB matches raw types, so a user's `WorkUnitAdapter` for a *different*
     * work-unit type (e.g. Kafka) would wrongly suppress the HTTP default. ObjectProvider
     * resolves against the full generic type, so only a `WorkUnitAdapter<HttpExchange>`
     * bean is picked up here.
     */
    @Bean
    @ConditionalOnMissingBean
    public open fun canonicalLogFilter(
        properties: CanonicalLogHttpProperties,
        adapter: ObjectProvider<WorkUnitAdapter<HttpExchange>>,
        writer: ObjectProvider<CanonicalLineWriter>,
        sampler: ObjectProvider<CanonicalLogSampler>,
    ): CanonicalLogFilter = CanonicalLogFilter(
        adapter = adapter.getIfAvailable { HttpWorkUnitAdapter() },
        writer = writer.getIfAvailable { LogstashCanonicalLineWriter() },
        excludePaths = properties.excludePaths,
        sampler = sampler.getIfAvailable { CanonicalLogSampler { true } },
    )

    @Bean
    public open fun canonicalLogFilterRegistration(
        filter: CanonicalLogFilter,
    ): FilterRegistrationBean<CanonicalLogFilter> {
        val registration = FilterRegistrationBean(filter)
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }
}
