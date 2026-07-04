package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogMdc
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter
import io.github.alexhumphreys.canonicallog.servlet.HttpExchange
import io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter
import io.github.alexhumphreys.canonicallog.servlet.runCanonicalHttpRequest
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

/**
 * Route resolver for the Spring MVC world: prefer Spring's own matched-pattern attribute,
 * falling back to the framework-neutral [HttpWorkUnitAdapter.ROUTE_ATTRIBUTE] so glue that
 * publishes routes the servlet-generic way still works under Spring.
 */
internal val springRouteResolver: (HttpServletRequest) -> String? = {
    (it.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)
        ?: it.getAttribute(HttpWorkUnitAdapter.ROUTE_ATTRIBUTE) as? String
}

/**
 * Servlet filter that opens a canonical work unit for each HTTP request and emits
 * exactly one canonical log line when the request completes — including for
 * asynchronous handlers (suspend controllers, `Callable`/`DeferredResult` returns,
 * SSE streams).
 *
 * This is the Spring-flavoured caller of the shared, framework-neutral HTTP lifecycle in
 * `canonical-log-servlet` — the whole `doFilterInternal` body is one call to
 * [runCanonicalHttpRequest], which owns the open → chain → sync/async emit split → unbind
 * sequence and the telemetry-never-fails-the-request policy (see it for details). What stays
 * Spring-specific here:
 *  - [OncePerRequestFilter] registration semantics (its once-per-request guard, its
 *    `shouldNotFilter` hook) — part of this filter's contract.
 *  - [AntPathMatcher] exclude-paths, for back-compat with existing `canonical-log.http
 *    .exclude-paths` values (the servlet module's [io.github.alexhumphreys.canonicallog
 *    .servlet.PathExclusions] is deliberately simpler and not ant-style).
 *  - the default adapter's route resolution via [springRouteResolver] (Spring's
 *    `BEST_MATCHING_PATTERN_ATTRIBUTE`).
 *
 * Both collaborators are injectable (the auto-configuration resolves user beans):
 *  - [adapter] describes/enriches the work unit. To add uniform fields (e.g. a tenant
 *    ID from a header), compose rather than subclass — write a
 *    `WorkUnitAdapter<HttpExchange>` that delegates `describe`/`enrich` to
 *    [HttpWorkUnitAdapter] and then puts its extra fields.
 *  - [writer] is the sink for the finalized line; defaults to the logstash
 *    `"canonical"` logger via [LogstashCanonicalLineWriter].
 *
 * Two knobs control which requests produce a line:
 *  - [excludePaths] (ant-style patterns matched against `request.requestURI`, the
 *    same value `url_path` reports) skips the work unit entirely — no context, no
 *    line. Use for health probes and other traffic that should cost nothing.
 *  - [sampler] is consulted after enrichment, so it sees the complete line (status,
 *    duration, error fields) and can implement "always keep errors, sample healthy
 *    200s" — see [CanonicalLogSampler]. Defaults to emit-all.
 */
public class CanonicalLogFilter(
    private val adapter: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(springRouteResolver),
    private val writer: CanonicalLineWriter = LogstashCanonicalLineWriter(),
    private val excludePaths: List<String> = emptyList(),
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
) : OncePerRequestFilter() {

    private val pathMatcher = AntPathMatcher()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        excludePaths.any { pathMatcher.match(it, request.requestURI) }

    @OptIn(DelicateCanonicalLogApi::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        runCanonicalHttpRequest(request, response, adapter, writer, sampler) {
            filterChain.doFilter(request, response)
        }
    }
}
