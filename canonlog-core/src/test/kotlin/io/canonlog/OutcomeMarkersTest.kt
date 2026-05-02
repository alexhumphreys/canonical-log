package io.canonlog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private val capturingAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("outcome_kind", outcome::class.simpleName)
        ctx.put("duration_ms", outcome.durationMs)
        if (outcome is Outcome.Threw) {
            ctx.put("outcome_cause_class", outcome.cause::class.simpleName)
        }
    }
}

@OptIn(DelicateCanonicalLogApi::class)
class OutcomeMarkersTest : DescribeSpec({

    describe("markFailed") {
        it("sets error=true and error_reason, plus extra fields") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.markFailed("validation_failed", "field" to "email", "code" to "missing")

            val snap = ctx.snapshot()
            snap["error"] shouldBe true
            snap["error_reason"] shouldBe "validation_failed"
            snap["field"] shouldBe "email"
            snap["code"] shouldBe "missing"
        }

        it("last call wins") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.markFailed("first")
            ctx.markFailed("second")

            ctx.snapshot()["error_reason"] shouldBe "second"
        }

        it("CanonicalLog.markFailed is a no-op without an active context") {
            threadLocalContext.set(null)
            CanonicalLog.markFailed("noop")
        }

        it("CanonicalLog.markFailed routes to the active context") {
            var snap: Map<String, Any> = emptyMap()
            withCanonicalLogBlocking(
                adapter = capturingAdapter,
                input = "wu",
                emit = { snap = it.snapshot() },
            ) {
                CanonicalLog.markFailed("business_rule", "kind" to "out_of_stock")
            }
            snap["error"] shouldBe true
            snap["error_reason"] shouldBe "business_rule"
            snap["kind"] shouldBe "out_of_stock"
        }
    }

    describe("markDegraded") {
        it("sets degraded=true and degraded_reason without setting error") {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.now()))
            ctx.markDegraded("cache_fallback", "primary" to "search-svc")

            val snap = ctx.snapshot()
            snap["degraded"] shouldBe true
            snap["degraded_reason"] shouldBe "cache_fallback"
            snap["primary"] shouldBe "search-svc"
            snap.containsKey("error") shouldBe false
        }
    }

    describe("Outcome propagation") {
        it("clean return produces Outcome.Completed") {
            var capturedOutcome: Outcome? = null
            val recordingAdapter = object : WorkUnitAdapter<String> {
                override fun describe(input: String) = WorkUnit(input, "t", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    capturedOutcome = outcome
                }
            }

            withCanonicalLogBlocking(recordingAdapter, "wu", { }) { "ok" }

            capturedOutcome.shouldBeInstanceOf<Outcome.Completed>()
        }

        it("thrown exception produces Outcome.Threw with the cause") {
            var capturedOutcome: Outcome? = null
            val recordingAdapter = object : WorkUnitAdapter<String> {
                override fun describe(input: String) = WorkUnit(input, "t", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    capturedOutcome = outcome
                }
            }

            runCatching {
                withCanonicalLogBlocking(recordingAdapter, "wu", { }) {
                    throw IllegalArgumentException("bad")
                }
            }

            val threw = capturedOutcome.shouldBeInstanceOf<Outcome.Threw>()
            threw.cause.shouldBeInstanceOf<IllegalArgumentException>()
            threw.cause.message shouldBe "bad"
        }
    }
})
