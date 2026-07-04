package io.github.alexhumphreys.canonicallog.servlet

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Framework-neutral servlet filter that opens a canonical work unit per HTTP request and
 * emits exactly one canonical log line when it completes — including for asynchronous
 * handlers (`AsyncContext`-based streaming, deferred results). A plain `jakarta.servlet`
 * `HttpFilter`, so it drops into any servlet stack (Jetty, Tomcat, Jersey-on-servlet,
 * Dropwizard) with no Spring dependency; the Spring starter has its own filter that shares
 * the same lifecycle via [runCanonicalHttpRequest].
 *
 * All the request-lifecycle behaviour lives in [runCanonicalHttpRequest] (see it for the
 * async/exception/cancellation semantics and the telemetry-never-fails-the-request policy).
 * This filter is a thin caller that adds two servlet-registration concerns:
 *
 *  - **Once-per-request guard.** A request attribute ([FILTERED_ATTRIBUTE]) is set for the
 *    duration of the lifecycle; if it's already set (a FORWARD/ERROR re-dispatch), the chain
 *    runs straight through so no second work unit opens — the same semantics Spring's
 *    `OncePerRequestFilter` provides its filter.
 *  - **[includeRequest].** Returning `false` skips the work unit entirely (no context, no
 *    line) — use [PathExclusions.matcher] for health probes and other traffic that should
 *    cost nothing. Defaults to including every request.
 *
 * [writer] has no default because the right sink is deployment-specific (a Dropwizard app
 * wires its `EventJsonLayout`-friendly writer; the Spring starter picks the logstash one).
 */
public class CanonicalLogServletFilter @JvmOverloads constructor(
    private val adapter: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(),
    private val writer: CanonicalLineWriter,
    private val includeRequest: (HttpServletRequest) -> Boolean = { true },
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
) : HttpFilter() {

    @OptIn(DelicateCanonicalLogApi::class)
    override fun doFilter(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        // FORWARD/ERROR re-dispatches re-enter the filter on the same request; open the work
        // unit only on the first pass, and let inner dispatches flow straight through.
        val alreadyFiltered = request.getAttribute(FILTERED_ATTRIBUTE) != null
        if (alreadyFiltered || !includeRequest(request)) {
            chain.doFilter(request, response)
            return
        }

        request.setAttribute(FILTERED_ATTRIBUTE, true)
        try {
            runCanonicalHttpRequest(request, response, adapter, writer, sampler) {
                chain.doFilter(request, response)
            }
        } finally {
            request.removeAttribute(FILTERED_ATTRIBUTE)
        }
    }

    public companion object {
        /** Request attribute marking that the filter has already opened a work unit for this request. */
        public const val FILTERED_ATTRIBUTE: String =
            "io.github.alexhumphreys.canonicallog.servlet.filtered"
    }
}
