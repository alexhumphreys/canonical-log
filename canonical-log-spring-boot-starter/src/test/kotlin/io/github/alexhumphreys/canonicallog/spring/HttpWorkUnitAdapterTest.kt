package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.Outcome
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.util.concurrent.CancellationException

@OptIn(DelicateCanonicalLogApi::class)
private fun ctx(): CanonicalLogContext = CanonicalLogContext(
    io.github.alexhumphreys.canonicallog.WorkUnit("wu-1", "http", java.time.Instant.now()),
)

private fun exchange(
    method: String = "GET",
    uri: String = "/posts/1",
    status: Int = 200,
    requestId: String? = null,
    matchedRoute: String? = null,
): HttpExchange {
    val req = MockHttpServletRequest(method, uri)
    if (requestId != null) req.addHeader("X-Request-Id", requestId)
    if (matchedRoute != null) req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, matchedRoute)
    val res = MockHttpServletResponse()
    res.status = status
    return HttpExchange(req, res)
}

@OptIn(DelicateCanonicalLogApi::class)
class HttpWorkUnitAdapterTest : DescribeSpec({

    val adapter = HttpWorkUnitAdapter()

    describe("describe") {
        it("uses X-Request-Id when present") {
            adapter.describe(exchange(requestId = "req-42")).id shouldBe "req-42"
        }
        it("falls back to a generated UUID when missing") {
            val id = adapter.describe(exchange()).id
            id.length shouldBe 36
        }
    }

    describe("enrich on Outcome.Completed") {
        it("populates the basic HTTP fields") {
            val c = ctx()
            adapter.enrich(
                c,
                exchange(method = "POST", uri = "/posts/42", status = 201, matchedRoute = "/posts/{id}"),
                Outcome.Completed(12L),
            )

            val s = c.snapshot()
            s["http_request_method"] shouldBe "POST"
            s["url_path"] shouldBe "/posts/42"
            s["http_route"] shouldBe "/posts/{id}"
            s["http_response_status_code"] shouldBe 201
            s["http_request_duration_ms"] shouldBe 12L
            s["work_unit_kind"] shouldBe "http"
            s.containsKey("error") shouldBe false
        }

        it("omits http_route when no template was matched (e.g. 404 before routing)") {
            val c = ctx()
            adapter.enrich(c, exchange(uri = "/no-such-thing", status = 404), Outcome.Completed(1L))

            val s = c.snapshot()
            s["url_path"] shouldBe "/no-such-thing"
            s.containsKey("http_route") shouldBe false
        }

        it("does not flag 2xx as error") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 200), Outcome.Completed(1L))
            c.snapshot().containsKey("error") shouldBe false
        }

        it("does not flag 4xx as error (handler decides via markFailed)") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 404), Outcome.Completed(1L))
            c.snapshot().containsKey("error") shouldBe false
        }

        it("flags 5xx as error with default reason 'server_error'") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 503), Outcome.Completed(1L))

            val s = c.snapshot()
            s["error"] shouldBe true
            s["error_reason"] shouldBe "server_error"
        }

        it("defers to handler-set error_reason on a 5xx") {
            val c = ctx()
            c.markFailed("upstream_timeout")
            adapter.enrich(c, exchange(status = 503), Outcome.Completed(1L))

            val s = c.snapshot()
            s["error"] shouldBe true
            s["error_reason"] shouldBe "upstream_timeout"
        }
    }

    describe("enrich on Outcome.Threw") {
        it("sets error, error_class, and a default error_reason") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 500), Outcome.Threw(5L, IllegalStateException("boom")))

            val s = c.snapshot()
            s["error"] shouldBe true
            s["error_class"] shouldBe "java.lang.IllegalStateException"
            s["error_reason"] shouldBe "exception"
            // Status is already 5xx; not overridden.
            s["http_response_status_code"] shouldBe 500
        }

        it("overrides http_response_status_code to 500 when Threw and captured status is still pre-throw default") {
            val c = ctx()
            // Exchange status is 200 (the default before the throw); container will set 500
            // AFTER the filter unwinds, but we don't see that. Adapter corrects.
            adapter.enrich(c, exchange(status = 200), Outcome.Threw(5L, RuntimeException("boom")))

            val s = c.snapshot()
            s["http_response_status_code"] shouldBe 500
            s["error"] shouldBe true
            s["error_class"] shouldBe "java.lang.RuntimeException"
        }

        it("defers to handler-set error_reason when an exception is thrown") {
            val c = ctx()
            c.markFailed("validation_failed")
            adapter.enrich(c, exchange(status = 500), Outcome.Threw(5L, IllegalStateException("boom")))

            val s = c.snapshot()
            s["error"] shouldBe true
            s["error_reason"] shouldBe "validation_failed"
            s["error_class"] shouldBe "java.lang.IllegalStateException"
        }
    }

    describe("enrich on Outcome.Cancelled") {
        it("sets cancelled=true with a default cancel_reason and no error fields") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 200), Outcome.Cancelled(5L, CancellationException("gone")))

            val s = c.snapshot()
            s["cancelled"] shouldBe true
            s["cancel_reason"] shouldBe "cancelled"
            // Cancellation is not a failure — it must not pollute error rates.
            s.containsKey("error") shouldBe false
            s.containsKey("error_class") shouldBe false
            s.containsKey("error_reason") shouldBe false
        }

        it("reports 499 when the captured status is still the pre-commit default") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 200), Outcome.Cancelled(5L, CancellationException()))

            c.snapshot()["http_response_status_code"] shouldBe 499
        }

        it("keeps an already-set error status instead of overriding to 499") {
            val c = ctx()
            adapter.enrich(c, exchange(status = 503), Outcome.Cancelled(5L, CancellationException()))

            c.snapshot()["http_response_status_code"] shouldBe 503
        }

        it("defers to a pre-set cancel_reason") {
            val c = ctx()
            c.put("cancel_reason", "async_timeout")
            adapter.enrich(c, exchange(status = 200), Outcome.Cancelled(5L, CancellationException()))

            c.snapshot()["cancel_reason"] shouldBe "async_timeout"
        }
    }
})
