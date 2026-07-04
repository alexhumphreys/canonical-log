package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val scopeAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("enriched_kind", outcome::class.simpleName)
    }
}

private val seedingScopeAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun seed(ctx: CanonicalLogContext, input: String) {
        // A seed that reads the ambient binding sees work_unit_id bound already.
        ctx.put("seeded_field", "from_seed")
        ctx.put("bound_id_at_seed", currentCanonicalContext()?.workUnit?.id)
    }
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("enriched_kind", outcome::class.simpleName)
    }
}

/**
 * Direct contract for the open/close primitive that backs [withCanonicalLogBlocking] and the
 * servlet filter. The closure and filter paths exercise it end-to-end elsewhere; this pins the
 * pieces callback-style callers (a Micrometer `ObservationHandler`) rely on in isolation.
 */
@OptIn(DelicateCanonicalLogApi::class)
class CanonicalWorkUnitScopeTest : DescribeSpec({

    afterEach { threadLocalContext.set(null) }

    describe("open/unbind binding") {
        it("binds the context on open and restores the previous binding on unbind") {
            currentCanonicalContext() shouldBe null
            val scope = openCanonicalWorkUnit(scopeAdapter, "wu")
            currentCanonicalContext() shouldBe scope.context
            scope.unbind()
            currentCanonicalContext() shouldBe null
        }

        it("records nesting when opened inside an active unit, and restores the outer on unbind") {
            val outer = openCanonicalWorkUnit(scopeAdapter, "outer")
            val inner = openCanonicalWorkUnit(scopeAdapter, "inner")

            inner.context.snapshot()[CanonicalFields.PARENT_WORK_UNIT_ID] shouldBe outer.context.workUnit.id
            inner.context.snapshot()[CanonicalFields.WORK_UNIT_DEPTH] shouldBe 1L
            currentCanonicalContext() shouldBe inner.context

            inner.unbind()
            currentCanonicalContext() shouldBe outer.context
            outer.context.snapshot().containsKey(CanonicalFields.PARENT_WORK_UNIT_ID) shouldBe false
            outer.unbind()
        }
    }

    describe("seed at open") {
        it("runs seed once at open, after the threadlocal bind, and its fields land on the line") {
            val scope = openCanonicalWorkUnit(seedingScopeAdapter, "wu")
            // Seed already ran during open, before the block/enrich — the field is present now.
            scope.context.snapshot()["seeded_field"] shouldBe "from_seed"
            // Seed saw the context bound (work_unit_id in MDC / threadlocal), per the doc'd choice.
            scope.context.snapshot()["bound_id_at_seed"] shouldBe "wu"
            scope.context.snapshot().containsKey(CanonicalFields.SEED_ERROR) shouldBe false
            scope.unbind()
        }

        it("swallows a throwing seed, recording it on the line; the scope still finalizes") {
            val throwingSeedAdapter = object : WorkUnitAdapter<String> {
                override fun describe(input: String) = WorkUnit(input, "test", Instant.now())
                override fun seed(ctx: CanonicalLogContext, input: String) {
                    throw IllegalStateException("seed boom")
                }
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
            }
            val scope = openCanonicalWorkUnit(throwingSeedAdapter, "wu")
            scope.context.snapshot()[CanonicalFields.SEED_ERROR] shouldBe true
            scope.context.snapshot()[CanonicalFields.SEED_ERROR_CLASS] shouldBe "java.lang.IllegalStateException"
            scope.unbind()
        }
    }

    describe("outcomeFor classification") {
        it("maps null/CancellationException/other to Completed/Cancelled/Threw") {
            val scope = openCanonicalWorkUnit(scopeAdapter, "wu")
            scope.outcomeFor(null).shouldBeInstanceOf<Outcome.Completed>()
            scope.outcomeFor(CancellationException("x")).shouldBeInstanceOf<Outcome.Cancelled>()
            scope.outcomeFor(IllegalStateException("x")).shouldBeInstanceOf<Outcome.Threw>()
            scope.unbind()
        }
    }

    describe("enrich and emit guards") {
        it("enrich runs the adapter; emit publishes the snapshot") {
            val scope = openCanonicalWorkUnit(scopeAdapter, "wu")
            scope.enrich(scopeAdapter, "wu", scope.outcomeFor(null))
            var emitted: Map<String, Any> = emptyMap()
            scope.unbind()
            scope.emit { emitted = it.snapshot() }
            emitted["enriched_kind"] shouldBe "Completed"
        }

        it("swallows a throwing adapter, recording it on the line") {
            val throwingAdapter = object : WorkUnitAdapter<String> {
                override fun describe(input: String) = WorkUnit(input, "test", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    throw IllegalStateException("enrich boom")
                }
            }
            val scope = openCanonicalWorkUnit(throwingAdapter, "wu")
            scope.enrich(throwingAdapter, "wu", scope.outcomeFor(null))
            scope.unbind()

            scope.context.snapshot()[CanonicalFields.ENRICH_ERROR] shouldBe true
        }

        it("swallows a throwing emit rather than propagating") {
            val scope = openCanonicalWorkUnit(scopeAdapter, "wu")
            scope.unbind()
            // Must not throw.
            scope.emit { throw IllegalStateException("emit boom") }
        }
    }
})
