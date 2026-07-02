package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant

private val nestingAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

/** Records every emitted line keyed by work unit id, plus the emit order. */
private class EmitRecorder {
    val order = mutableListOf<String>()
    val lines = mutableMapOf<String, Map<String, Any>>()
    val emit: EmitFn = { ctx ->
        order += ctx.workUnit.id
        lines[ctx.workUnit.id] = ctx.snapshot()
    }
}

/**
 * Pins the nested work-unit contract — "inner shadows outer":
 *
 *  - while an inner unit is open, ambient contributions route to the inner
 *    accumulator only;
 *  - when it closes, the outer unit resumes receiving contributions;
 *  - each unit emits its own line, inner first;
 *  - a nested unit's line carries `parent_work_unit_id` (immediate parent only)
 *    and `work_unit_depth` (absent on top-level lines — absent means 0);
 *  - no aggregation of inner fields into the outer line;
 *  - identical across blocking/suspend entry points, nested in any combination.
 */
class NestedWorkUnitTest : DescribeSpec({

    describe("blocking inside blocking") {
        it("routes ambient contributions to the inner unit only, resumes the outer, emits inner first with parent_work_unit_id") {
            val rec = EmitRecorder()
            withCanonicalLogBlocking(nestingAdapter, "outer", rec.emit) {
                CanonicalLog.put("outer_before", "yes")
                withCanonicalLogBlocking(nestingAdapter, "inner", rec.emit) {
                    CanonicalLog.put("inner_field", "yes")
                }
                CanonicalLog.put("outer_after", "yes")
            }

            rec.order shouldBe listOf("inner", "outer")

            val inner = rec.lines.getValue("inner")
            inner["inner_field"] shouldBe "yes"
            inner["parent_work_unit_id"] shouldBe "outer"
            inner["work_unit_depth"] shouldBe 1L
            inner.containsKey("outer_before") shouldBe false
            inner.containsKey("outer_after") shouldBe false

            val outer = rec.lines.getValue("outer")
            outer["outer_before"] shouldBe "yes"
            outer["outer_after"] shouldBe "yes"
            outer.containsKey("inner_field") shouldBe false
            outer.containsKey("parent_work_unit_id") shouldBe false
            outer.containsKey("work_unit_depth") shouldBe false

            currentCanonicalContext() shouldBe null
        }

        it("three-deep nesting: each line carries its immediate parent and its depth") {
            val rec = EmitRecorder()
            withCanonicalLogBlocking(nestingAdapter, "a", rec.emit) {
                withCanonicalLogBlocking(nestingAdapter, "b", rec.emit) {
                    withCanonicalLogBlocking(nestingAdapter, "c", rec.emit) {
                        CanonicalLog.put("depth", 3L)
                    }
                }
            }

            rec.order shouldBe listOf("c", "b", "a")
            rec.lines.getValue("c")["parent_work_unit_id"] shouldBe "b"
            rec.lines.getValue("b")["parent_work_unit_id"] shouldBe "a"
            rec.lines.getValue("a").containsKey("parent_work_unit_id") shouldBe false
            rec.lines.getValue("c")["work_unit_depth"] shouldBe 2L
            rec.lines.getValue("b")["work_unit_depth"] shouldBe 1L
            rec.lines.getValue("a").containsKey("work_unit_depth") shouldBe false
            rec.lines.getValue("c")["depth"] shouldBe 3L
            rec.lines.getValue("b").containsKey("depth") shouldBe false
        }
    }

    describe("suspend inside suspend") {
        it("inner shadows outer across dispatcher switches; outer resumes after; inner line carries parent_work_unit_id") {
            val rec = EmitRecorder()
            runBlocking {
                withCanonicalLog(nestingAdapter, "outer", rec.emit) {
                    CanonicalLog.put("outer_before", "yes")
                    withCanonicalLog(nestingAdapter, "inner", rec.emit) {
                        CanonicalLog.put("inner_field", "yes")
                        withContext(Dispatchers.IO) {
                            CanonicalLog.put("inner_io", "yes")
                        }
                    }
                    CanonicalLog.put("outer_after", "yes")
                    withContext(Dispatchers.IO) {
                        CanonicalLog.put("outer_io_after", "yes")
                    }
                }
            }

            rec.order shouldBe listOf("inner", "outer")

            val inner = rec.lines.getValue("inner")
            inner["inner_field"] shouldBe "yes"
            inner["inner_io"] shouldBe "yes"
            inner["parent_work_unit_id"] shouldBe "outer"
            inner["work_unit_depth"] shouldBe 1L
            inner.containsKey("outer_before") shouldBe false
            inner.containsKey("outer_after") shouldBe false

            val outer = rec.lines.getValue("outer")
            outer["outer_before"] shouldBe "yes"
            outer["outer_after"] shouldBe "yes"
            outer["outer_io_after"] shouldBe "yes"
            outer.containsKey("inner_field") shouldBe false
            outer.containsKey("inner_io") shouldBe false
            outer.containsKey("parent_work_unit_id") shouldBe false
            outer.containsKey("work_unit_depth") shouldBe false

            currentCanonicalContext() shouldBe null
        }
    }

    describe("mixed variants") {
        it("blocking inner inside suspend outer: same contract") {
            val rec = EmitRecorder()
            runBlocking {
                withCanonicalLog(nestingAdapter, "outer", rec.emit) {
                    CanonicalLog.put("outer_field", "yes")
                    withCanonicalLogBlocking(nestingAdapter, "inner", rec.emit) {
                        CanonicalLog.put("inner_field", "yes")
                    }
                    CanonicalLog.put("outer_after", "yes")
                }
            }

            rec.order shouldBe listOf("inner", "outer")
            rec.lines.getValue("inner")["parent_work_unit_id"] shouldBe "outer"
            rec.lines.getValue("inner")["work_unit_depth"] shouldBe 1L
            rec.lines.getValue("inner")["inner_field"] shouldBe "yes"
            rec.lines.getValue("inner").containsKey("outer_field") shouldBe false
            rec.lines.getValue("outer")["outer_field"] shouldBe "yes"
            rec.lines.getValue("outer")["outer_after"] shouldBe "yes"
            rec.lines.getValue("outer").containsKey("inner_field") shouldBe false
        }

        it("suspend inner inside blocking outer: same contract, including inner dispatcher switches") {
            val rec = EmitRecorder()
            withCanonicalLogBlocking(nestingAdapter, "outer", rec.emit) {
                CanonicalLog.put("outer_field", "yes")
                runBlocking {
                    withCanonicalLog(nestingAdapter, "inner", rec.emit) {
                        CanonicalLog.put("inner_field", "yes")
                        withContext(Dispatchers.IO) {
                            CanonicalLog.put("inner_io", "yes")
                        }
                    }
                }
                CanonicalLog.put("outer_after", "yes")
            }

            rec.order shouldBe listOf("inner", "outer")
            val inner = rec.lines.getValue("inner")
            inner["parent_work_unit_id"] shouldBe "outer"
            inner["work_unit_depth"] shouldBe 1L
            inner["inner_field"] shouldBe "yes"
            inner["inner_io"] shouldBe "yes"
            inner.containsKey("outer_field") shouldBe false
            val outer = rec.lines.getValue("outer")
            outer["outer_field"] shouldBe "yes"
            outer["outer_after"] shouldBe "yes"
            outer.containsKey("inner_field") shouldBe false
            outer.containsKey("inner_io") shouldBe false

            currentCanonicalContext() shouldBe null
        }
    }
})
