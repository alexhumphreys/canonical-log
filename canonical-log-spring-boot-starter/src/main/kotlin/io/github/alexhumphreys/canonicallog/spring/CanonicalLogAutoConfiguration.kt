package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogMdc
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter
import io.github.alexhumphreys.canonicallog.servlet.HttpExchange
import io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter
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
public open class CanonicalLogAutoConfiguration(properties: CanonicalLogHttpProperties) {

    init {
        // Process-wide switch, applied once at startup: it must also govern the core
        // entry points and propagation helpers, which a per-filter flag couldn't reach.
        CanonicalLogMdc.enabled = properties.mdcEnabled
    }

    /**
     * The default sink, shared with the scheduling starter (todo 020). Registered as a real
     * `@Bean` guarded by `@ConditionalOnMissingBean(CanonicalLineWriter)` — safe here because
     * [CanonicalLineWriter] has no type parameter, so CoMB can't misfire the way it would for
     * the raw-typed `WorkUnitAdapter<T>` (see [canonicalLogFilter]). A user [CanonicalLineWriter]
     * bean therefore overrides the sink for both the HTTP filter and scheduled-job lines.
     */
    @Bean
    @ConditionalOnMissingBean(CanonicalLineWriter::class)
    public open fun canonicalLineWriter(): CanonicalLineWriter = LogstashCanonicalLineWriter()

    /**
     * User beans win over the defaults: a `WorkUnitAdapter<HttpExchange>` bean replaces
     * [HttpWorkUnitAdapter] (compose with it for extra uniform fields — see
     * [CanonicalLogFilter]), a [CanonicalLineWriter] bean replaces the logstash sink (via the
     * [canonicalLineWriter] default above), a [CanonicalLogSampler] bean replaces the emit-all
     * default.
     *
     * Adapter and sampler resolution uses [ObjectProvider], not `@ConditionalOnMissingBean` on
     * default beans: CoMB matches raw types, so a user's `WorkUnitAdapter` for a *different*
     * work-unit type (e.g. Kafka) would wrongly suppress the HTTP default. ObjectProvider
     * resolves against the full generic type, so only a `WorkUnitAdapter<HttpExchange>`
     * bean is picked up here. The writer is injected directly — see [canonicalLineWriter].
     */
    @Bean
    @ConditionalOnMissingBean
    public open fun canonicalLogFilter(
        properties: CanonicalLogHttpProperties,
        adapter: ObjectProvider<WorkUnitAdapter<HttpExchange>>,
        writer: CanonicalLineWriter,
        sampler: ObjectProvider<CanonicalLogSampler>,
    ): CanonicalLogFilter = CanonicalLogFilter(
        // Default adapter reads the Spring route (BEST_MATCHING_PATTERN) first — a bare
        // HttpWorkUnitAdapter() would only see the servlet-generic ROUTE_ATTRIBUTE and lose
        // http_route under Spring MVC.
        adapter = adapter.getIfAvailable { HttpWorkUnitAdapter(springRouteResolver) },
        writer = writer,
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
