package io.github.alexhumphreys.canonicallog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.time.Instant

/**
 * Adapter whose lifecycle hooks are injectable, so each test cell can make a hook
 * reenter the library (ambient writes, nested units, throws-after-partial-work).
 */
private class HookAdapter(
    val onSeed: (CanonicalLogContext) -> Unit = {},
    val onEnrich: (CanonicalLogContext) -> Unit = {},
) : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun seed(ctx: CanonicalLogContext, input: String) = onSeed(ctx)
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) = onEnrich(ctx)
}

private val plainAdapter = HookAdapter()

/** Records every emitted line keyed by work unit id, plus the emit order. */
private class ReentrancyEmitRecorder {
    val order = mutableListOf<String>()
    val lines = mutableMapOf<String, Map<String, Any>>()
    val emit: EmitFn = { ctx ->
        order += ctx.workUnit.id
        lines[ctx.workUnit.id] = ctx.snapshot()
    }
}

/**
 * Pins what happens when a lifecycle hook (seed / enrich / emit) calls back into the
 * library, for each entry point (blocking, suspend, open/close scope). The contracts
 * being pinned live in the KDoc of [EmitFn] and [WorkUnitAdapter.seed]/`enrich`:
 *
 *  - seed and enrich run with the unit bound on the closure entry points: ambient
 *    writes land (though `ctx` is the supported form); on the open/close-scope path
 *    enrich may run after `unbind()`, where ambient writes no-op but `ctx.put` lands;
 *  - emit observes a finalized line on **every** entry point: the finalized unit is
 *    not bound during emit (threadlocal and MDC hold the enclosing binding), so
 *    ambient writes inside emit never land on the line being emitted — no-ops at top
 *    level, the enclosing unit's line when nested;
 *  - emit receives the live context: a `ctx.put` inside one writer is visible to a
 *    later writer in the same emit, but not to the first writer's own snapshot;
 *  - a nested work unit opened inside seed/enrich nests under the open unit; opened
 *    inside emit it nests under the *enclosing* binding (top-level if none) — never
 *    under the finalized unit;
 *  - an appender/sink that contributes canonical fields cannot recurse through emit,
 *    because its contributions no-op with no unit bound;
 *  - a hook that throws *after* partial reentrant work still gets the standard
 *    swallow-and-record treatment, and the binding is still restored.
 */
class LifecycleReentrancyTest : DescribeSpec({

    describe("blocking entry point") {

        it("ambient writes inside seed and enrich land on the line (unit is bound)") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(
                onSeed = {
                    currentCanonicalContext() shouldBe it
                    CanonicalLog.put("seed_ambient", "yes")
                },
                onEnrich = {
                    currentCanonicalContext() shouldBe it
                    CanonicalLog.put("enrich_ambient", "yes")
                },
            )
            withCanonicalLogBlocking(adapter, "u", rec.emit) {}
            rec.lines.getValue("u")["seed_ambient"] shouldBe "yes"
            rec.lines.getValue("u")["enrich_ambient"] shouldBe "yes"
        }

        it("ambient writes inside emit are no-ops at top level; no binding, no MDC id") {
            val rec = ReentrancyEmitRecorder()
            var lineAfterAmbientPut: Map<String, Any>? = null
            withCanonicalLogBlocking(plainAdapter, "u", emit = { ctx ->
                currentCanonicalContext().shouldBeNull()
                MDC.get(CanonicalLogMdc.KEY).shouldBeNull()
                CanonicalLog.put("emit_ambient", "lost")
                CanonicalLog.increment("emit_ambient_count")
                lineAfterAmbientPut = ctx.snapshot()
                rec.emit(ctx)
            }) {}
            lineAfterAmbientPut!!.containsKey("emit_ambient") shouldBe false
            lineAfterAmbientPut!!.containsKey("emit_ambient_count") shouldBe false
            rec.lines.getValue("u").containsKey("emit_ambient") shouldBe false
        }

        it("ambient writes inside a nested unit's emit land on the enclosing unit, never the finalized line") {
            val rec = ReentrancyEmitRecorder()
            withCanonicalLogBlocking(plainAdapter, "outer", rec.emit) {
                withCanonicalLogBlocking(plainAdapter, "inner", emit = { ctx ->
                    currentCanonicalContext()?.workUnit?.id shouldBe "outer"
                    CanonicalLog.put("from_inner_emit", "yes")
                    rec.emit(ctx)
                }) {}
            }
            rec.lines.getValue("inner").containsKey("from_inner_emit") shouldBe false
            rec.lines.getValue("outer")["from_inner_emit"] shouldBe "yes"
        }

        it("emit receives the live context: ctx.put in the first writer is seen by the second, not by the first's own snapshot") {
            var first: Map<String, Any>? = null
            var second: Map<String, Any>? = null
            val firstWriter: EmitFn = { ctx ->
                first = ctx.snapshot()
                ctx.put("late_field", "from_first_writer")
            }
            val secondWriter: EmitFn = { ctx -> second = ctx.snapshot() }
            withCanonicalLogBlocking(plainAdapter, "u", emit = { firstWriter(it); secondWriter(it) }) {}
            first!!.containsKey("late_field") shouldBe false
            second!!["late_field"] shouldBe "from_first_writer"
        }

        it("a nested unit opened inside seed nests under the open unit; the outer line is unaffected") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onSeed = {
                withCanonicalLogBlocking(plainAdapter, "seed_child", rec.emit) {
                    CanonicalLog.put("child_field", "yes")
                }
            })
            withCanonicalLogBlocking(adapter, "outer", rec.emit) {}
            rec.order shouldBe listOf("seed_child", "outer")
            rec.lines.getValue("seed_child")["parent_work_unit_id"] shouldBe "outer"
            rec.lines.getValue("seed_child")["child_field"] shouldBe "yes"
            rec.lines.getValue("outer").containsKey("child_field") shouldBe false
        }

        it("a nested unit opened inside enrich nests under the open unit") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onEnrich = {
                withCanonicalLogBlocking(plainAdapter, "enrich_child", rec.emit) {}
            })
            withCanonicalLogBlocking(adapter, "outer", rec.emit) {}
            rec.order shouldBe listOf("enrich_child", "outer")
            rec.lines.getValue("enrich_child")["parent_work_unit_id"] shouldBe "outer"
        }

        it("a unit opened inside emit is top-level: the finalized unit is not its parent") {
            val rec = ReentrancyEmitRecorder()
            withCanonicalLogBlocking(plainAdapter, "outer", emit = { ctx ->
                withCanonicalLogBlocking(plainAdapter, "emit_child", rec.emit) {}
                rec.emit(ctx)
            }) {}
            rec.order shouldBe listOf("emit_child", "outer")
            rec.lines.getValue("emit_child").containsKey("parent_work_unit_id") shouldBe false
            rec.lines.getValue("emit_child").containsKey("work_unit_depth") shouldBe false
        }

        it("a sink that contributes canonical fields cannot recurse: no binding during emit, so it no-ops once") {
            var calls = 0
            var line: Map<String, Any>? = null
            lateinit var writer: EmitFn
            writer = { ctx ->
                calls++
                check(calls <= 5) { "unbounded emit recursion" }
                // Models an appender instrumented to contribute — and to write again
                // (recursing into the same writer) whenever a unit is active.
                CanonicalLog.increment("recursed")
                if (currentCanonicalContext() != null) writer(ctx)
                line = ctx.snapshot()
            }
            withCanonicalLogBlocking(plainAdapter, "u", emit = { writer(it) }) {}
            calls shouldBe 1
            line!!.containsKey("recursed") shouldBe false
        }

        it("a seed that throws after partial reentrant work: nested line emitted, ambient write kept, seed_error recorded, binding restored") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onSeed = {
                CanonicalLog.put("seed_partial", "yes")
                withCanonicalLogBlocking(plainAdapter, "seed_child", rec.emit) {}
                throw IllegalStateException("seed boom")
            })
            val result = withCanonicalLogBlocking(adapter, "outer", rec.emit) { "ok" }
            result shouldBe "ok"
            currentCanonicalContext().shouldBeNull()
            val outer = rec.lines.getValue("outer")
            outer["seed_partial"] shouldBe "yes"
            outer[CanonicalFields.SEED_ERROR] shouldBe true
            rec.lines.getValue("seed_child")["parent_work_unit_id"] shouldBe "outer"
        }

        it("an enrich that throws after partial reentrant work: enrich_error recorded, binding restored, block result unaffected") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onEnrich = {
                CanonicalLog.put("enrich_partial", "yes")
                throw IllegalStateException("enrich boom")
            })
            val result = withCanonicalLogBlocking(adapter, "u", rec.emit) { 42 }
            result shouldBe 42
            currentCanonicalContext().shouldBeNull()
            val line = rec.lines.getValue("u")
            line["enrich_partial"] shouldBe "yes"
            line[CanonicalFields.ENRICH_ERROR] shouldBe true
        }

        it("an emit that throws after partial reentrant work: line dropped, block result unaffected, binding restored") {
            val result = withCanonicalLogBlocking(plainAdapter, "u", emit = {
                CanonicalLog.put("emit_partial", "lost")
                throw IllegalStateException("emit boom")
            }) { "ok" }
            result shouldBe "ok"
            currentCanonicalContext().shouldBeNull()
        }
    }

    describe("suspend entry point") {

        it("ambient writes inside seed and enrich land on the line (unit is bound)") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(
                onSeed = { CanonicalLog.put("seed_ambient", "yes") },
                onEnrich = { CanonicalLog.put("enrich_ambient", "yes") },
            )
            runBlocking {
                withCanonicalLog(adapter, "u", rec.emit) {
                    withContext(Dispatchers.IO) { CanonicalLog.put("io_field", "yes") }
                }
            }
            val line = rec.lines.getValue("u")
            line["seed_ambient"] shouldBe "yes"
            line["enrich_ambient"] shouldBe "yes"
            line["io_field"] shouldBe "yes"
        }

        it("ambient writes inside emit are no-ops at top level; no binding, no MDC id — matching the blocking path") {
            val rec = ReentrancyEmitRecorder()
            var lineAfterAmbientPut: Map<String, Any>? = null
            runBlocking {
                withCanonicalLog(plainAdapter, "u", emit = { ctx ->
                    currentCanonicalContext().shouldBeNull()
                    MDC.get(CanonicalLogMdc.KEY).shouldBeNull()
                    CanonicalLog.put("emit_ambient", "lost")
                    CanonicalLog.increment("emit_ambient_count")
                    lineAfterAmbientPut = ctx.snapshot()
                    rec.emit(ctx)
                }) {
                    withContext(Dispatchers.IO) { CanonicalLog.put("io_field", "yes") }
                }
            }
            lineAfterAmbientPut!!.containsKey("emit_ambient") shouldBe false
            lineAfterAmbientPut!!.containsKey("emit_ambient_count") shouldBe false
            lineAfterAmbientPut!!["io_field"] shouldBe "yes"
        }

        it("the binding and MDC id are back after emit: the enclosing coroutine continues undisturbed") {
            val rec = ReentrancyEmitRecorder()
            runBlocking {
                withCanonicalLog(plainAdapter, "outer", rec.emit) {
                    withCanonicalLog(plainAdapter, "inner", rec.emit) {}
                    // Emit for "inner" already ran; the outer unit must be re-bound.
                    currentCanonicalContext()?.workUnit?.id shouldBe "outer"
                    MDC.get(CanonicalLogMdc.KEY) shouldBe "outer"
                    CanonicalLog.put("outer_after_inner_emit", "yes")
                }
            }
            rec.lines.getValue("outer")["outer_after_inner_emit"] shouldBe "yes"
            currentCanonicalContext().shouldBeNull()
        }

        it("ambient writes inside a nested unit's emit land on the enclosing unit, never the finalized line") {
            val rec = ReentrancyEmitRecorder()
            runBlocking {
                withCanonicalLog(plainAdapter, "outer", rec.emit) {
                    withCanonicalLog(plainAdapter, "inner", emit = { ctx ->
                        currentCanonicalContext()?.workUnit?.id shouldBe "outer"
                        MDC.get(CanonicalLogMdc.KEY) shouldBe "outer"
                        CanonicalLog.put("from_inner_emit", "yes")
                        rec.emit(ctx)
                    }) {}
                }
            }
            rec.lines.getValue("inner").containsKey("from_inner_emit") shouldBe false
            rec.lines.getValue("outer")["from_inner_emit"] shouldBe "yes"
        }

        it("a nested unit opened inside seed nests under the open unit (seed runs bound on the caller's thread)") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onSeed = {
                withCanonicalLogBlocking(plainAdapter, "seed_child", rec.emit) {}
            })
            runBlocking { withCanonicalLog(adapter, "outer", rec.emit) {} }
            rec.order shouldBe listOf("seed_child", "outer")
            rec.lines.getValue("seed_child")["parent_work_unit_id"] shouldBe "outer"
        }

        it("a unit opened inside emit is top-level: the finalized unit is not its parent") {
            val rec = ReentrancyEmitRecorder()
            runBlocking {
                withCanonicalLog(plainAdapter, "outer", emit = { ctx ->
                    withCanonicalLogBlocking(plainAdapter, "emit_child", rec.emit) {}
                    rec.emit(ctx)
                }) {}
            }
            rec.order shouldBe listOf("emit_child", "outer")
            rec.lines.getValue("emit_child").containsKey("parent_work_unit_id") shouldBe false
        }

        it("a sink that contributes canonical fields cannot recurse: no binding during emit, so it no-ops once") {
            var calls = 0
            var line: Map<String, Any>? = null
            lateinit var writer: EmitFn
            writer = { ctx ->
                calls++
                check(calls <= 5) { "unbounded emit recursion" }
                CanonicalLog.increment("recursed")
                if (currentCanonicalContext() != null) writer(ctx)
                line = ctx.snapshot()
            }
            runBlocking { withCanonicalLog(plainAdapter, "u", emit = { writer(it) }) {} }
            calls shouldBe 1
            line!!.containsKey("recursed") shouldBe false
        }

        it("an emit that throws after partial reentrant work: line dropped, result unaffected, block exception still rethrown when it threw") {
            // Completed block: result survives a throwing emit.
            val result = runBlocking {
                withCanonicalLog(plainAdapter, "u", emit = {
                    CanonicalLog.put("emit_partial", "lost")
                    throw IllegalStateException("emit boom")
                }) { "ok" }
            }
            result shouldBe "ok"
            // Failing block: the block's own exception wins over the emit failure.
            shouldThrow<IllegalArgumentException> {
                runBlocking {
                    withCanonicalLog(plainAdapter, "u2", emit = {
                        throw IllegalStateException("emit boom")
                    }) { throw IllegalArgumentException("block boom") }
                }
            }
            currentCanonicalContext().shouldBeNull()
        }
    }

    describe("open/close scope entry point") {

        it("seed runs bound at open: ambient writes land; enrich after unbind: ambient no-ops but ctx.put lands") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(
                onSeed = { CanonicalLog.put("seed_ambient", "yes") },
                onEnrich = { ctx ->
                    // The async hand-off shape: enrich runs after unbind, so ambient
                    // writes have nowhere to go — ctx is the only supported channel.
                    currentCanonicalContext().shouldBeNull()
                    CanonicalLog.put("enrich_ambient", "lost")
                    ctx.put("enrich_direct", "yes")
                },
            )
            val scope = openCanonicalWorkUnit(adapter, "u")
            CanonicalLog.put("body_field", "yes")
            val outcome = scope.outcomeFor(null)
            scope.unbind()
            scope.enrich(adapter, "u", outcome)
            scope.emit(rec.emit)
            val line = rec.lines.getValue("u")
            line["seed_ambient"] shouldBe "yes"
            line["body_field"] shouldBe "yes"
            line["enrich_direct"] shouldBe "yes"
            line.containsKey("enrich_ambient") shouldBe false
        }

        it("ambient writes inside emit are no-ops: emit runs after unbind by the caller invariant") {
            val rec = ReentrancyEmitRecorder()
            val scope = openCanonicalWorkUnit(plainAdapter, "u")
            scope.enrich(plainAdapter, "u", scope.outcomeFor(null))
            scope.unbind()
            scope.emit { ctx ->
                currentCanonicalContext().shouldBeNull()
                CanonicalLog.put("emit_ambient", "lost")
                rec.emit(ctx)
            }
            rec.lines.getValue("u").containsKey("emit_ambient") shouldBe false
        }

        it("a nested unit opened inside seed nests under the open unit") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onSeed = {
                withCanonicalLogBlocking(plainAdapter, "seed_child", rec.emit) {}
            })
            val scope = openCanonicalWorkUnit(adapter, "outer")
            scope.enrich(adapter, "outer", scope.outcomeFor(null))
            scope.unbind()
            scope.emit(rec.emit)
            rec.order shouldBe listOf("seed_child", "outer")
            rec.lines.getValue("seed_child")["parent_work_unit_id"] shouldBe "outer"
        }

        it("a unit opened inside emit is top-level, and a contributing sink cannot recurse") {
            val rec = ReentrancyEmitRecorder()
            var calls = 0
            val scope = openCanonicalWorkUnit(plainAdapter, "outer")
            scope.enrich(plainAdapter, "outer", scope.outcomeFor(null))
            scope.unbind()
            scope.emit { ctx ->
                calls++
                check(calls <= 5) { "unbounded emit recursion" }
                CanonicalLog.increment("recursed")
                withCanonicalLogBlocking(plainAdapter, "emit_child", rec.emit) {}
                rec.emit(ctx)
            }
            calls shouldBe 1
            rec.lines.getValue("emit_child").containsKey("parent_work_unit_id") shouldBe false
            rec.lines.getValue("outer").containsKey("recursed") shouldBe false
        }

        it("a seed that throws after partial reentrant work still leaves the unit bound and usable") {
            val rec = ReentrancyEmitRecorder()
            val adapter = HookAdapter(onSeed = {
                CanonicalLog.put("seed_partial", "yes")
                throw IllegalStateException("seed boom")
            })
            val scope = openCanonicalWorkUnit(adapter, "u")
            // The open must survive the throwing seed: unit bound, body contributions land.
            currentCanonicalContext() shouldBe scope.context
            CanonicalLog.put("body_field", "yes")
            scope.enrich(adapter, "u", scope.outcomeFor(null))
            scope.unbind()
            scope.emit(rec.emit)
            currentCanonicalContext().shouldBeNull()
            val line = rec.lines.getValue("u")
            line["seed_partial"] shouldBe "yes"
            line["body_field"] shouldBe "yes"
            line[CanonicalFields.SEED_ERROR] shouldBe true
        }
    }
})
