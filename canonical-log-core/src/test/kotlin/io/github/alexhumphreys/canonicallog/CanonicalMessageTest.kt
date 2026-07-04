package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class CanonicalMessageTest : DescribeSpec({

    describe("canonicalLineMessage") {
        it("composes an HTTP line from method, route, status, duration") {
            val fields = mapOf<String, Any>(
                CanonicalFields.HTTP_REQUEST_METHOD to "GET",
                CanonicalFields.HTTP_ROUTE to "/posts/{id}",
                CanonicalFields.URL_PATH to "/posts/1",
                CanonicalFields.HTTP_RESPONSE_STATUS_CODE to 200L,
                CanonicalFields.HTTP_REQUEST_DURATION_MS to 12L,
            )
            canonicalLineMessage(fields) shouldBe "GET /posts/{id} 200 12ms"
        }

        it("falls back to url_path when no route matched") {
            val fields = mapOf<String, Any>(
                CanonicalFields.HTTP_REQUEST_METHOD to "GET",
                CanonicalFields.URL_PATH to "/unmatched",
                CanonicalFields.HTTP_RESPONSE_STATUS_CODE to 404L,
                CanonicalFields.HTTP_REQUEST_DURATION_MS to 3L,
            )
            canonicalLineMessage(fields) shouldBe "GET /unmatched 404 3ms"
        }

        it("appends an error hint with the reason when error=true") {
            val fields = mapOf<String, Any>(
                CanonicalFields.HTTP_REQUEST_METHOD to "GET",
                CanonicalFields.HTTP_ROUTE to "/posts/{id}",
                CanonicalFields.HTTP_RESPONSE_STATUS_CODE to 404L,
                CanonicalFields.HTTP_REQUEST_DURATION_MS to 3L,
                CanonicalFields.ERROR to true,
                CanonicalFields.ERROR_REASON to "post_not_found",
            )
            canonicalLineMessage(fields) shouldBe "GET /posts/{id} 404 3ms error=post_not_found"
        }

        it("appends a bare error hint when error=true with no reason") {
            val fields = mapOf<String, Any>(
                CanonicalFields.HTTP_REQUEST_METHOD to "GET",
                CanonicalFields.URL_PATH to "/x",
                CanonicalFields.HTTP_RESPONSE_STATUS_CODE to 500L,
                CanonicalFields.ERROR to true,
            )
            canonicalLineMessage(fields) shouldBe "GET /x 500 error"
        }

        it("uses work_unit_kind + id for a non-HTTP unit") {
            val fields = mapOf<String, Any>(
                CanonicalFields.WORK_UNIT_KIND to "scheduled_job",
                CanonicalFields.WORK_UNIT_ID to "abc-123",
            )
            canonicalLineMessage(fields) shouldBe "scheduled_job abc-123"
        }

        it("degrades to work_unit when even kind is absent") {
            canonicalLineMessage(emptyMap()) shouldBe "work_unit"
        }
    }
})
