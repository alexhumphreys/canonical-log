package io.github.alexhumphreys.canonicallog.scheduling

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.support.ScheduledTaskObservationContext

/**
 * Instruments `@Scheduled` methods with a canonical log line, transparently — the job author
 * writes a plain `@Scheduled fun`, adds this starter to the classpath, and each run emits a line
 * with `work_unit_kind=scheduled_job` plus whatever contributors (JDBC, OkHttp) and the body's
 * `CanonicalLog.put` calls add.
 *
 * **How it hooks in.** Spring emits an observation around every scheduled run *if* an
 * [ObservationRegistry] is set on the [ScheduledTaskRegistrar]; otherwise the registry is NOOP and
 * nothing fires. This auto-config resolves a registry (the application's own bean if present — e.g.
 * from actuator — else a private one), registers [CanonicalScheduledTaskObservationHandler] on it,
 * and wires it onto the registrar via a [SchedulingConfigurer]. Because it uses the framework's own
 * scheduled-task observation, it needs no AOP and works regardless of proxying.
 *
 * Requires `@EnableScheduling` in the application (as scheduling always does). Opt out with
 * `canonical-log.scheduling.enabled=false`. Override the job identity/fields by registering a
 * `WorkUnitAdapter<ScheduledTaskObservationContext>` bean (replaces [ScheduledJobWorkUnitAdapter]).
 *
 * **Shared sink (todo 020).** The line is written through a [CanonicalLineWriter] bean, so a user
 * writer bean overrides the sink for scheduled-job lines *and* HTTP lines uniformly. This starter
 * registers its own default [LogstashCanonicalLineWriter] guarded by
 * `@ConditionalOnMissingBean(CanonicalLineWriter)` so it works standalone; when the HTTP umbrella
 * starter is also present, [AutoConfiguration]'s `afterName` ordering (by string class name, so no
 * compile dependency between starters) makes the umbrella's default register first and this one
 * back off — exactly one default writer regardless of which starters are on the classpath.
 */
@AutoConfiguration(
    afterName = ["io.github.alexhumphreys.canonicallog.spring.CanonicalLogAutoConfiguration"],
)
@ConditionalOnClass(ScheduledTaskObservationContext::class, ObservationRegistry::class)
@ConditionalOnProperty(
    name = ["canonical-log.scheduling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class CanonicalSchedulingAutoConfiguration {

    /**
     * Default sink, shared with the HTTP starter (todo 020). `@ConditionalOnMissingBean` is safe
     * because [CanonicalLineWriter] has no type parameter (unlike the raw-typed adapter). When the
     * HTTP umbrella starter is present it registers this bean first (see the class KDoc's ordering
     * note), so this default backs off and both starters use the one writer.
     */
    @Bean
    @ConditionalOnMissingBean(CanonicalLineWriter::class)
    public open fun canonicalLineWriter(): CanonicalLineWriter = LogstashCanonicalLineWriter()

    /**
     * Registers the canonical handler on the resolved observation registry and sets that registry
     * on the scheduling registrar. A user `WorkUnitAdapter<ScheduledTaskObservationContext>` bean
     * wins over the default; resolution uses [ObjectProvider] so an adapter for a *different*
     * work-unit type isn't mistaken for this one (same reasoning as the HTTP filter's adapter). The
     * [writer] is the shared [CanonicalLineWriter] bean; the handler emits via `writer::write`.
     */
    @Bean
    public open fun canonicalSchedulingConfigurer(
        registryProvider: ObjectProvider<ObservationRegistry>,
        adapterProvider: ObjectProvider<WorkUnitAdapter<ScheduledTaskObservationContext>>,
        writer: CanonicalLineWriter,
    ): SchedulingConfigurer {
        val registry = registryProvider.getIfAvailable { ObservationRegistry.create() }
        val adapter = adapterProvider.getIfAvailable { ScheduledJobWorkUnitAdapter() }
        registry.observationConfig()
            .observationHandler(CanonicalScheduledTaskObservationHandler(adapter, writer::write))
        return SchedulingConfigurer { registrar: ScheduledTaskRegistrar ->
            registrar.setObservationRegistry(registry)
        }
    }
}
