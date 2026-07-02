package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

@OptIn(DelicateCanonicalLogApi::class)
class CanonicalLogContextTest : DescribeSpec({
    describe("CanonicalLogContext") {
        it("put and increment work on a fresh context") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.put("string_field", "hello")
            ctx.put("number_field", 42L)
            ctx.increment("counter", 3L)
            ctx.increment("counter", 2L)

            val snap = ctx.snapshot()
            snap["string_field"] shouldBe "hello"
            snap["number_field"] shouldBe 42L
            snap["counter"] shouldBe 5L
        }

        it("null values are omitted from put") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.put("present", "x")
            ctx.put("absent", null)

            val snap = ctx.snapshot()
            snap.containsKey("present") shouldBe true
            snap.containsKey("absent") shouldBe false
        }

        it("incrementing a field that was previously put as a non-Long drops the increment and marks the conflict") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.put("misused", "i should have been a long")

            // Must not throw: increments run inside contributors embedded in
            // application-critical paths (JDBC listener, OkHttp interceptor).
            ctx.increment("misused")

            val snap = ctx.snapshot()
            snap["misused"] shouldBe "i should have been a long"
            snap["canonical_log_type_conflict"] shouldBe true
            snap["canonical_log_type_conflict_key"] shouldBe "misused"
            snap["canonical_log_type_conflict_type"] shouldBe "kotlin.String"
        }

        it("markFailed with extras passes through to the snapshot") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.markFailed("validation_failed", "field" to "email", "code" to 422L)

            val snap = ctx.snapshot()
            snap["error"] shouldBe true
            snap["error_reason"] shouldBe "validation_failed"
            snap["field"] shouldBe "email"
            snap["code"] shouldBe 422L
        }

        it("snapshot is a defensive copy") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.put("a", 1L)
            val snap = ctx.snapshot()
            ctx.put("b", 2L)

            snap.containsKey("a") shouldBe true
            snap.containsKey("b") shouldBe false
        }
    }
})
