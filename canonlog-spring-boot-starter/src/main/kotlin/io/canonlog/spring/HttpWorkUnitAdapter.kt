package io.canonlog.spring

import io.canonlog.CanonicalLogContext
import io.canonlog.Outcome
import io.canonlog.WorkUnit
import io.canonlog.WorkUnitAdapter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import java.util.UUID

public data class HttpExchange(
    val request: HttpServletRequest,
    val response: HttpServletResponse,
)

public class HttpWorkUnitAdapter : WorkUnitAdapter<HttpExchange> {
    override fun describe(input: HttpExchange): WorkUnit = WorkUnit(
        id = input.request.getHeader("X-Request-Id") ?: UUID.randomUUID().toString(),
        kind = "http",
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: HttpExchange, outcome: Outcome) {
        ctx.put("http_request_method", input.request.method)
        ctx.put("http_route", input.request.requestURI)
        ctx.put("http_response_status_code", input.response.status.toLong())
        ctx.put("http_request_duration_ms", outcome.durationMs)
        ctx.put("work_unit_id", ctx.workUnit.id)
        ctx.put("work_unit_kind", ctx.workUnit.kind)

        val current = ctx.snapshot()
        when (outcome) {
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
                if (input.response.status < STATUS_SERVER_ERROR) {
                    ctx.put("http_response_status_code", STATUS_SERVER_ERROR.toLong())
                }
            }
            is Outcome.Completed -> {
                if (input.response.status >= STATUS_SERVER_ERROR && current["error"] != true) {
                    ctx.put("error", true)
                    if (current["error_reason"] == null) {
                        ctx.put("error_reason", "server_error")
                    }
                }
            }
        }
    }

    private companion object {
        const val STATUS_SERVER_ERROR = 500
    }
}
