package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.slf4j.MDC
import java.time.Instant

/**
 * Pins the zero-dependency MDC seeding decorator: named MDC keys copied to canonical fields
 * at [WorkUnitAdapter.seed] time, nulls dropped, delegate's own seed preserved, and MDC
 * key → field renames honoured.
 */
@OptIn(DelicateCanonicalLogApi::class)
class MdcSeedingAdapterTest : DescribeSpec({

    // A delegate that records its seed ran (so we can prove the decorator calls through) and
    // contributes an identity field, mirroring a real adapter.
    class RecordingDelegate : WorkUnitAdapter<Unit> {
        var seeded = false
        override fun describe(input: Unit): WorkUnit = WorkUnit("id", "test", Instant.now())
        override fun seed(ctx: CanonicalLogContext, input: Unit) {
            seeded = true
            ctx.put("delegate_seeded", true)
        }
        override fun enrich(ctx: CanonicalLogContext, input: Unit, outcome: Outcome) {}
    }

    fun freshCtx() = CanonicalLogContext(WorkUnit("id", "test", Instant.now()))

    afterEach { MDC.clear() }

    describe("MdcSeedingAdapter.seed") {
        it("copies named MDC keys to canonical fields") {
            MDC.put("trace_id", "abc123")
            MDC.put("span_id", "def456")
            val ctx = freshCtx()
            val adapter = MdcSeedingAdapter(
                RecordingDelegate(),
                mapOf("trace_id" to CanonicalFields.TRACE_ID, "span_id" to CanonicalFields.SPAN_ID),
            )

            adapter.seed(ctx, Unit)

            val snap = ctx.snapshot()
            snap[CanonicalFields.TRACE_ID] shouldBe "abc123"
            snap[CanonicalFields.SPAN_ID] shouldBe "def456"
        }

        it("omits fields whose MDC key is absent (nulls dropped, cost nothing)") {
            // Only trace_id is set; span_id is absent.
            MDC.put("trace_id", "abc123")
            val ctx = freshCtx()
            val adapter = MdcSeedingAdapter(
                RecordingDelegate(),
                mapOf("trace_id" to CanonicalFields.TRACE_ID, "span_id" to CanonicalFields.SPAN_ID),
            )

            adapter.seed(ctx, Unit)

            val snap = ctx.snapshot()
            snap shouldContainKey CanonicalFields.TRACE_ID
            snap shouldNotContainKey CanonicalFields.SPAN_ID
        }

        it("still runs the delegate's seed") {
            val delegate = RecordingDelegate()
            val ctx = freshCtx()
            val adapter = MdcSeedingAdapter(delegate, mapOf("trace_id" to CanonicalFields.TRACE_ID))

            adapter.seed(ctx, Unit)

            delegate.seeded shouldBe true
            ctx.snapshot()["delegate_seeded"] shouldBe true
        }

        it("renames a vendor MDC key to the canonical field (dd.trace_id -> trace_id)") {
            MDC.put("dd.trace_id", "42")
            val ctx = freshCtx()
            val adapter = MdcSeedingAdapter(
                RecordingDelegate(),
                mapOf("dd.trace_id" to CanonicalFields.TRACE_ID),
            )

            adapter.seed(ctx, Unit)

            val snap = ctx.snapshot()
            snap[CanonicalFields.TRACE_ID] shouldBe "42"
            snap shouldNotContainKey "dd.trace_id"
        }
    }
})
