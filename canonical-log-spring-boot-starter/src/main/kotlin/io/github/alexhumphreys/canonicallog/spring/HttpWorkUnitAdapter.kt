package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.time.Instant
import java.util.UUID

public class HttpExchange(
    public val request: HttpServletRequest,
    public val response: HttpServletResponse,
)

public class HttpWorkUnitAdapter : WorkUnitAdapter<HttpExchange> {
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
        val matchedRoute = input.request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        val capturedStatus = input.response.status

        ctx.put("http_request_method", method)
        ctx.put("url_path", rawPath)
        // http_route is the matched template (e.g. /posts/{id}), used for grouping in
        // dashboards. Omitted entirely when no template was matched (e.g. 404 before
        // routing) so queries on http_route don't surface unmatched garbage.
        ctx.put("http_route", matchedRoute)
        ctx.put("http_request_duration_ms", outcome.durationMs)
        ctx.put("work_unit_id", ctx.workUnit.id)
        ctx.put("work_unit_kind", ctx.workUnit.kind)

        val current = ctx.snapshot()
        val effectiveStatus = when (outcome) {
            is Outcome.Threw -> {
                ctx.put("error", true)
                ctx.put("error_class", outcome.cause::class.qualifiedName ?: "unknown")
                if (current["error_reason"] == null) {
                    ctx.put("error_reason", "exception")
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
                ctx.put("cancelled", true)
                if (current["cancel_reason"] == null) {
                    ctx.put("cancel_reason", "cancelled")
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
                if (capturedStatus >= STATUS_SERVER_ERROR && current["error"] != true) {
                    ctx.put("error", true)
                    if (current["error_reason"] == null) {
                        ctx.put("error_reason", "server_error")
                    }
                }
                capturedStatus
            }
        }
        ctx.put("http_response_status_code", effectiveStatus)
    }

    private companion object {
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_CLIENT_CLOSED_REQUEST = 499
        const val STATUS_SERVER_ERROR = 500
    }
}
