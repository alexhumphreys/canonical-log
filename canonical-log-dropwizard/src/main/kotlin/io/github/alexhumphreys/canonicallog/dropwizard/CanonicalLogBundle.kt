package io.github.alexhumphreys.canonicallog.dropwizard

import io.dropwizard.core.Configuration
import io.dropwizard.core.ConfiguredBundle
import io.dropwizard.core.setup.Environment
import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogMdc
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.MdcCanonicalLineWriter
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.servlet.CanonicalLogServletFilter
import io.github.alexhumphreys.canonicallog.servlet.HttpExchange
import io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter
import io.github.alexhumphreys.canonicallog.servlet.PathExclusions
import jakarta.servlet.DispatcherType
import java.util.EnumSet

/**
 * Dropwizard [ConfiguredBundle] that gives a Dropwizard 4 service one wide canonical log line
 * per **application-port** request — route template, status, duration, error/cancel semantics
 * identical to the Spring starter — for the cost of a single `bootstrap.addBundle(...)`.
 *
 * It wires the three pieces a Dropwizard adopter needs:
 *  1. [CanonicalLogServletFilter] on the application servlet environment, mapped to every
 *     path with `REQUEST` dispatch only — the filter's own once-per-request guard covers
 *     container re-dispatches — which opens the work unit and emits the line.
 *  2. [JerseyRouteCaptureFilter] on the Jersey environment, which publishes the matched route
 *     template so `http_route` appears on the line.
 *  3. Nothing on the **admin** environment: admin-port traffic (Dropwizard healthchecks,
 *     metrics) never opens a work unit, so it costs nothing for free. Health/readiness
 *     endpoints served on the *application* port that shouldn't produce a line need
 *     [excludePaths] (e.g. `listOf("/ping")`).
 *
 * ### Default writer: [MdcCanonicalLineWriter]
 * Dropwizard's stock JSON layout is `EventJsonLayout`, which renders slf4j **MDC** but not
 * logstash `StructuredArguments`. So the default sink flattens each canonical line into MDC;
 * configure the layout to surface those keys:
 * ```yaml
 * logging:
 *   appenders:
 *     - type: console
 *       layout:
 *         type: json
 *         flattenMdc: true   # canonical fields become top-level JSON keys
 * ```
 * The trade-off is that MDC is `String`-valued, so counts/durations arrive stringified.
 * Adopters who want typed fields can pass a
 * [LogstashCanonicalLineWriter][io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter]
 * (add a dependency on `canonical-log-logstash`) and route the `"canonical"` logger to a
 * logstash-encoder appender — a pointer, not this module's concern.
 *
 * ### Customization
 * Constructor parameters, not a builder (a canonical-log design decision — constructors are
 * fine). Replace the [adapter] to add uniform fields (compose with [HttpWorkUnitAdapter],
 * don't subclass), the [writer] to change the sink, or the [sampler] to drop healthy traffic.
 *
 * ### Error semantics under Dropwizard's exception mappers
 * With Dropwizard's default exception mappers on (`server.registerDefaultExceptionMappers`,
 * default `true`), Jersey maps an uncaught resource exception to a 500 JSON body *before* the
 * request unwinds through this filter — so the line reports `error=true` /
 * `error_reason=server_error` via the completed-5xx heuristic, but **without** `error_class`.
 * Disabling the default mappers (or any path where the container rethrows) lets the exception
 * reach the filter as `Outcome.Threw`, adding `error_class` (the servlet container's wrapper
 * type, e.g. `jakarta.servlet.ServletException`). A handler `CanonicalLog.markFailed(reason)`
 * returning a 4xx/5xx yields `error=true` + your `error_reason` with no `error_class`, exactly
 * as under the Spring starter.
 *
 * ### MDC correlation
 * `work_unit_id` MDC correlation on ordinary handler logs works with no extra wiring — the
 * servlet filter installs it. The process-wide [CanonicalLogMdc.enabled] switch (set once at
 * startup) is the opt-out; there is deliberately no bundle flag for it.
 */
public class CanonicalLogBundle @JvmOverloads constructor(
    private val adapter: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(),
    private val writer: CanonicalLineWriter = MdcCanonicalLineWriter(),
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
    private val excludePaths: List<String> = emptyList(),
) : ConfiguredBundle<Configuration> {

    override fun run(configuration: Configuration, environment: Environment) {
        environment.servlets()
            .addFilter(
                "canonical-log",
                CanonicalLogServletFilter(adapter, writer, PathExclusions.matcher(excludePaths), sampler),
            )
            // REQUEST dispatch only: the filter's own once-per-request attribute guard covers
            // FORWARD/ERROR container re-dispatches, so a single REQUEST mapping is enough.
            .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*")

        environment.jersey().register(JerseyRouteCaptureFilter::class.java)
    }
}
