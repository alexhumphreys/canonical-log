package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Pins the canonical vocabulary. The string *values* are the on-the-wire contract shared
 * across services in one observability stack — renaming a constant's value silently breaks
 * every dashboard querying it, so those values are asserted literally here. The emission
 * cases confirm the library actually writes under these keys, keeping the constants and the
 * code that uses them from drifting apart.
 */
@OptIn(DelicateCanonicalLogApi::class)
class CanonicalFieldsTest : DescribeSpec({

    describe("wire-contract values") {
        it("pins every published field name") {
            // Grouped to mirror CanonicalFields; a value change is a breaking wire change.
            CanonicalFields.ERROR shouldBe "error"
            CanonicalFields.ERROR_REASON shouldBe "error_reason"
            CanonicalFields.ERROR_CLASS shouldBe "error_class"
            CanonicalFields.DEGRADED shouldBe "degraded"
            CanonicalFields.DEGRADED_REASON shouldBe "degraded_reason"
            CanonicalFields.CANCELLED shouldBe "cancelled"
            CanonicalFields.CANCEL_REASON shouldBe "cancel_reason"

            CanonicalFields.WORK_UNIT_ID shouldBe "work_unit_id"
            CanonicalFields.WORK_UNIT_KIND shouldBe "work_unit_kind"
            CanonicalFields.PARENT_WORK_UNIT_ID shouldBe "parent_work_unit_id"
            CanonicalFields.WORK_UNIT_DEPTH shouldBe "work_unit_depth"

            CanonicalFields.TYPE_CONFLICT shouldBe "canonical_log_type_conflict"
            CanonicalFields.TYPE_CONFLICT_KEY shouldBe "canonical_log_type_conflict_key"
            CanonicalFields.TYPE_CONFLICT_TYPE shouldBe "canonical_log_type_conflict_type"
            CanonicalFields.ENRICH_ERROR shouldBe "canonical_log_enrich_error"
            CanonicalFields.ENRICH_ERROR_CLASS shouldBe "canonical_log_enrich_error_class"
            CanonicalFields.SEED_ERROR shouldBe "canonical_log_seed_error"
            CanonicalFields.SEED_ERROR_CLASS shouldBe "canonical_log_seed_error_class"

            CanonicalFields.TRACE_ID shouldBe "trace_id"
            CanonicalFields.SPAN_ID shouldBe "span_id"

            CanonicalFields.HTTP_REQUEST_METHOD shouldBe "http_request_method"
            CanonicalFields.URL_PATH shouldBe "url_path"
            CanonicalFields.HTTP_ROUTE shouldBe "http_route"
            CanonicalFields.HTTP_RESPONSE_STATUS_CODE shouldBe "http_response_status_code"
            CanonicalFields.HTTP_REQUEST_DURATION_MS shouldBe "http_request_duration_ms"
            CanonicalFields.X_REQUEST_ID_REJECTED shouldBe "x_request_id_rejected"

            CanonicalFields.HTTP_CLIENT_REQUEST_COUNT shouldBe "http_client_request_count"
            CanonicalFields.HTTP_CLIENT_REQUEST_DURATION_MS_TOTAL shouldBe "http_client_request_duration_ms_total"
            CanonicalFields.HTTP_CLIENT_4XX_COUNT shouldBe "http_client_4xx_count"
            CanonicalFields.HTTP_CLIENT_5XX_COUNT shouldBe "http_client_5xx_count"
            CanonicalFields.HTTP_CLIENT_ERROR_COUNT shouldBe "http_client_error_count"

            CanonicalFields.DB_QUERY_COUNT shouldBe "db_query_count"
            CanonicalFields.DB_EXECUTION_COUNT shouldBe "db_execution_count"
            CanonicalFields.DB_EXECUTION_DURATION_MS_TOTAL shouldBe "db_execution_duration_ms_total"
            CanonicalFields.DB_SLOW_EXECUTION_COUNT shouldBe "db_slow_execution_count"
            CanonicalFields.DB_EXECUTION_ERROR_COUNT shouldBe "db_execution_error_count"
        }

        it("has no duplicate values (a copy-paste bug would alias two fields)") {
            val values = allPublishedValues()
            values.toSet().size shouldBe values.size
        }

        it("MDC key mirrors the work_unit_id field constant") {
            CanonicalLogMdc.KEY shouldBe CanonicalFields.WORK_UNIT_ID
        }
    }

    describe("core writes under the published keys") {
        it("markFailed emits ERROR and ERROR_REASON") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.markFailed("boom")
            val snap = ctx.snapshot()
            snap[CanonicalFields.ERROR] shouldBe true
            snap[CanonicalFields.ERROR_REASON] shouldBe "boom"
        }

        it("markDegraded emits DEGRADED and DEGRADED_REASON") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.markDegraded("slow")
            val snap = ctx.snapshot()
            snap[CanonicalFields.DEGRADED] shouldBe true
            snap[CanonicalFields.DEGRADED_REASON] shouldBe "slow"
        }

        it("an increment/put type conflict reports under the TYPE_CONFLICT keys") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.put("clash", "not-a-long")
            ctx.increment("clash")
            val snap = ctx.snapshot()
            snap[CanonicalFields.TYPE_CONFLICT] shouldBe true
            snap[CanonicalFields.TYPE_CONFLICT_KEY] shouldBe "clash"
        }
    }
})

private fun allPublishedValues(): List<String> = listOf(
    CanonicalFields.ERROR,
    CanonicalFields.ERROR_REASON,
    CanonicalFields.ERROR_CLASS,
    CanonicalFields.DEGRADED,
    CanonicalFields.DEGRADED_REASON,
    CanonicalFields.CANCELLED,
    CanonicalFields.CANCEL_REASON,
    CanonicalFields.WORK_UNIT_ID,
    CanonicalFields.WORK_UNIT_KIND,
    CanonicalFields.PARENT_WORK_UNIT_ID,
    CanonicalFields.WORK_UNIT_DEPTH,
    CanonicalFields.TYPE_CONFLICT,
    CanonicalFields.TYPE_CONFLICT_KEY,
    CanonicalFields.TYPE_CONFLICT_TYPE,
    CanonicalFields.ENRICH_ERROR,
    CanonicalFields.ENRICH_ERROR_CLASS,
    CanonicalFields.SEED_ERROR,
    CanonicalFields.SEED_ERROR_CLASS,
    CanonicalFields.TRACE_ID,
    CanonicalFields.SPAN_ID,
    CanonicalFields.HTTP_REQUEST_METHOD,
    CanonicalFields.URL_PATH,
    CanonicalFields.HTTP_ROUTE,
    CanonicalFields.HTTP_RESPONSE_STATUS_CODE,
    CanonicalFields.HTTP_REQUEST_DURATION_MS,
    CanonicalFields.X_REQUEST_ID_REJECTED,
    CanonicalFields.HTTP_CLIENT_REQUEST_COUNT,
    CanonicalFields.HTTP_CLIENT_REQUEST_DURATION_MS_TOTAL,
    CanonicalFields.HTTP_CLIENT_4XX_COUNT,
    CanonicalFields.HTTP_CLIENT_5XX_COUNT,
    CanonicalFields.HTTP_CLIENT_ERROR_COUNT,
    CanonicalFields.DB_QUERY_COUNT,
    CanonicalFields.DB_EXECUTION_COUNT,
    CanonicalFields.DB_EXECUTION_DURATION_MS_TOTAL,
    CanonicalFields.DB_SLOW_EXECUTION_COUNT,
    CanonicalFields.DB_EXECUTION_ERROR_COUNT,
)
