package io.canonlog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val testAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(
        id = input,
        kind = "test",
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("outcome_kind", outcome::class.simpleName)
        ctx.put("duration_ms", outcome.durationMs)
    }
}

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
