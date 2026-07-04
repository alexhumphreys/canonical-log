package io.github.alexhumphreys.canonicallog.scheduling

import io.github.alexhumphreys.canonicallog.EmitFn
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.canonicalLineMessage
import io.micrometer.observation.ObservationRegistry
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
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
 */
@AutoConfiguration
@ConditionalOnClass(ScheduledTaskObservationContext::class, ObservationRegistry::class)
@ConditionalOnProperty(
    name = ["canonical-log.scheduling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class CanonicalSchedulingAutoConfiguration {

    /**
     * Registers the canonical handler on the resolved observation registry and sets that registry
     * on the scheduling registrar. A user `WorkUnitAdapter<ScheduledTaskObservationContext>` bean
     * wins over the default; resolution uses [ObjectProvider] so an adapter for a *different*
     * work-unit type isn't mistaken for this one (same reasoning as the HTTP filter's adapter).
     */
    @Bean
    public open fun canonicalSchedulingConfigurer(
        registryProvider: ObjectProvider<ObservationRegistry>,
        adapterProvider: ObjectProvider<WorkUnitAdapter<ScheduledTaskObservationContext>>,
    ): SchedulingConfigurer {
        val registry = registryProvider.getIfAvailable { ObservationRegistry.create() }
        val adapter = adapterProvider.getIfAvailable { ScheduledJobWorkUnitAdapter() }
        registry.observationConfig()
            .observationHandler(CanonicalScheduledTaskObservationHandler(adapter, canonicalEmit()))
        return SchedulingConfigurer { registrar: ScheduledTaskRegistrar ->
            registrar.setObservationRegistry(registry)
        }
    }

    /**
     * Default emit sink: log the snapshot to the `canonical` logger as logstash structured
     * arguments, identical to the HTTP starter's `LogstashCanonicalLineWriter`. Kept private here
     * rather than sharing the HTTP starter's writer type, to avoid coupling this starter to the
     * servlet umbrella — see `docs/todos/019-job-entry-point-ergonomics.md` for the planned
     * unification of the writer abstraction across entry points.
     */
    private fun canonicalEmit(): EmitFn {
        val logger = LoggerFactory.getLogger("canonical")
        return { ctx ->
            val snapshot = ctx.snapshot()
            logger.info(canonicalLineMessage(snapshot), StructuredArguments.entries(snapshot))
        }
    }
}
