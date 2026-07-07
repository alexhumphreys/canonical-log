package io.github.alexhumphreys.canonicallog.servlet

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.CancellationException

@OptIn(DelicateCanonicalLogApi::class)
private fun ctx(): CanonicalLogContext = CanonicalLogContext(
    WorkUnit("wu-1", "http", Instant.now()),
)

private fun exchange(
    method: String = "GET",
    uri: String = "/posts/1",
    status: Int = 200,
    requestId: String? = null,
    matchedRoute: String? = null,
): HttpExchange {
    val fake = FakeRequest(
        method = method,
        uri = uri,
        headers = requestId?.let { mapOf("X-Request-Id" to it) } ?: emptyMap(),
        responseStatus = status,
    )
    // Route templates flow through the framework-neutral ROUTE_ATTRIBUTE — the default
    // resolver the adapter uses when no framework-specific resolver is supplied.
    if (matchedRoute != null) fake.request.setAttribute(HttpWorkUnitAdapter.ROUTE_ATTRIBUTE, matchedRoute)
    return HttpExchange(fake.request, fake.response)
}

class HttpWorkUnitAdapterTest : DescribeSpec({

    val adapter = HttpWorkUnitAdapter()

    describe("describe") {
        it("uses X-Request-Id when present") {
            adapter.describe(exchange(requestId = "req-42")).id shouldBe "req-42"
        }
        it("passes a valid safe-charset header through unchanged") {
            adapter.describe(exchange(requestId = "abc-123.def_456")).id shouldBe "abc-123.def_456"
        }
        it("accepts a 128-char header (the boundary)") {
            val id = "a".repeat(128)
            adapter.describe(exchange(requestId = id)).id shouldBe id
        }
        it("falls back to a generated UUID when missing") {
            val id = adapter.describe(exchange()).id
            id.length shouldBe 36
        }
        it("falls back to a UUID for an empty header (treated as absent)") {
            adapter.describe(exchange(requestId = "")).id.length shouldBe 36
        }
        it("falls back to a UUID for an oversized header (>128 chars)") {
            adapter.describe(exchange(requestId = "a".repeat(129))).id.length shouldBe 36
        }
        it("falls back to a UUID for an invalid-charset header") {
            adapter.describe(exchange(requestId = "has space/and\nnewline")).id.length shouldBe 36
        }
    }

    describe("x_request_id_rejected marker") {
        fun enrichWith(requestId: String?): Map<String, Any?> {
            val c = ctx()
            adapter.enrich(c, exchange(requestId = requestId), Outcome.Completed(1L))
            return c.snapshot()
        }

        it("is absent for a valid header") {
            enrichWith("abc-123.def_456").containsKey("x_request_id_rejected") shouldBe false
        }
        it("is absent for a missing header (absent is normal, not a rejection)") {
            enrichWith(null).containsKey("x_request_id_rejected") shouldBe false
        }
        it("is absent for an empty header (treated as absent)") {
            enrichWith("").containsKey("x_request_id_rejected") shouldBe false
        }
        it("is true for an oversized header") {
            enrichWith("a".repeat(129))["x_request_id_rejected"] shouldBe true
        }
        it("is true for an invalid-charset header") {
            enrichWith("has space/and\nnewline")["x_request_id_rejected"] shouldBe true
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

        it("resolves http_route via ROUTE_ATTRIBUTE when set") {
            val c = ctx()
            adapter.enrich(c, exchange(uri = "/posts/7", matchedRoute = "/posts/{id}"), Outcome.Completed(1L))
            c.snapshot()["http_route"] shouldBe "/posts/{id}"
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
