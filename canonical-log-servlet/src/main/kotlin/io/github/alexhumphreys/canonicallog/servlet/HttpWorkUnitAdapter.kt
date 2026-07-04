package io.github.alexhumphreys.canonicallog.servlet

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import java.util.UUID

public class HttpExchange(
    public val request: HttpServletRequest,
    public val response: HttpServletResponse,
)

/**
 * Framework-neutral [WorkUnitAdapter] for a servlet HTTP request: captures
 * `http_request_method` / `url_path` / `http_route` / `http_response_status_code` /
 * `http_request_duration_ms`, and applies the status/error/cancellation heuristics that
 * every canonical HTTP line shares.
 *
 * The only framework-specific input is *where the matched route template comes from*, which
 * is injected as [routeResolver]. The default reads the [ROUTE_ATTRIBUTE] request attribute,
 * so any glue that publishes the matched route there (a Jersey/Dropwizard filter, plain
 * servlet routing) gets `http_route` for free. The Spring starter passes a resolver that
 * reads Spring MVC's `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` first.
 *
 * Compose, don't subclass: to add uniform fields (a tenant id from a header, say), write a
 * `WorkUnitAdapter<HttpExchange>` that delegates `describe`/`enrich` here and then puts its
 * extra fields.
 */
public class HttpWorkUnitAdapter(
    private val routeResolver: (HttpServletRequest) -> String? = {
        it.getAttribute(ROUTE_ATTRIBUTE) as? String
    },
) : WorkUnitAdapter<HttpExchange> {

    override fun describe(input: HttpExchange): WorkUnit = WorkUnit(
        id = input.request.getHeader("X-Request-Id") ?: UUID.randomUUID().toString(),
        kind = "http",
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: HttpExchange, outcome: Outcome) {
        // Capture all mutable request/response state in one pass. Reading the response
        // status twice could in principle yield inconsistent values if the container
        // is finalizing the response on another thread; not a known bug, just cheap
        // defence.
        val method = input.request.method
        val rawPath = input.request.requestURI
        val matchedRoute = routeResolver(input.request)
        val capturedStatus = input.response.status

        ctx.put(CanonicalFields.HTTP_REQUEST_METHOD, method)
        ctx.put(CanonicalFields.URL_PATH, rawPath)
        // http_route is the matched template (e.g. /posts/{id}), used for grouping in
        // dashboards. Omitted entirely when no template was matched (e.g. 404 before
        // routing) so queries on http_route don't surface unmatched garbage.
        ctx.put(CanonicalFields.HTTP_ROUTE, matchedRoute)
        ctx.put(CanonicalFields.HTTP_REQUEST_DURATION_MS, outcome.durationMs)
        ctx.put(CanonicalFields.WORK_UNIT_ID, ctx.workUnit.id)
        ctx.put(CanonicalFields.WORK_UNIT_KIND, ctx.workUnit.kind)

        val current = ctx.snapshot()
        val effectiveStatus = when (outcome) {
            is Outcome.Threw -> {
                ctx.put(CanonicalFields.ERROR, true)
                ctx.put(CanonicalFields.ERROR_CLASS, outcome.cause::class.qualifiedName ?: "unknown")
                if (current[CanonicalFields.ERROR_REASON] == null) {
                    ctx.put(CanonicalFields.ERROR_REASON, "exception")
                }
                // Uncaught exceptions are mapped to 5xx by the servlet container's outer
                // valve, but that happens AFTER our filter unwinds. If the captured status
                // is still the pre-throw default (typically 200), report 500 to match what
                // the client actually receives.
                if (capturedStatus < STATUS_SERVER_ERROR) STATUS_SERVER_ERROR else capturedStatus
            }
            is Outcome.Cancelled -> {
                // Cancellation is not a failure: cancelled=true, no error=true, so
                // client disconnects and request timeouts don't pollute error rates.
                ctx.put(CanonicalFields.CANCELLED, true)
                if (current[CanonicalFields.CANCEL_REASON] == null) {
                    ctx.put(CanonicalFields.CANCEL_REASON, "cancelled")
                }
                // A cancelled request rarely produced a real response status — the
                // captured value is usually the pre-commit default (200), and what the
                // container writes afterwards (often 500 on async timeout) happens
                // after this filter unwinds and is container-dependent. The line
                // reports 499 (nginx's "client closed request") as the convention for
                // cancelled HTTP work, keeping only an already-set error status.
                if (capturedStatus < STATUS_BAD_REQUEST) STATUS_CLIENT_CLOSED_REQUEST else capturedStatus
            }
            is Outcome.Completed -> {
                if (capturedStatus >= STATUS_SERVER_ERROR && current[CanonicalFields.ERROR] != true) {
                    ctx.put(CanonicalFields.ERROR, true)
                    if (current[CanonicalFields.ERROR_REASON] == null) {
                        ctx.put(CanonicalFields.ERROR_REASON, "server_error")
                    }
                }
                capturedStatus
            }
        }
        ctx.put(CanonicalFields.HTTP_RESPONSE_STATUS_CODE, effectiveStatus)
    }

    public companion object {
        /** Request attribute any framework glue may set to publish the matched route template. */
        public const val ROUTE_ATTRIBUTE: String =
            "io.github.alexhumphreys.canonicallog.servlet.route"

        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_CLIENT_CLOSED_REQUEST = 499
        private const val STATUS_SERVER_ERROR = 500
    }
}
