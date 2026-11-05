package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant

/**
 * Pins [CanonicalLogLimits]: past the field cap, new keys are dropped and counted (overwrites,
 * library-defined keys and enrich-time writes still land); over-long strings are stored as their
 * prefix and counted; neither ever throws; under the limits nothing changes.
 */
@OptIn(DelicateCanonicalLogApi::class)
class FieldGuardrailsTest : DescribeSpec({

    fun context(maxFields: Int = 256, maxValueLength: Int = 8192) =
        CanonicalLogContext(WorkUnit("id", "test", Instant.now()), CanonicalLogLimits(maxFields, maxValueLength))

    // The keys a test contributed, without the library's own markers.
    fun Map<String, Any>.userKeys() = keys.filterNot { it.startsWith("canonical_log_") }

    describe("field cap") {
        it("caps distinct keys and counts every dropped write, for any number of puts") {
            checkAll(Arb.int(1..40), Arb.int(0..120)) { cap, puts ->
                val ctx = context(maxFields = cap)
                repeat(puts) { ctx.put("item_$it", it.toLong()) }

                val snap = ctx.snapshot()
                val dropped = (puts - cap).coerceAtLeast(0)
                snap.userKeys().size shouldBe minOf(puts, cap)
                if (dropped > 0) {
                    snap[CanonicalFields.DROPPED_FIELD_COUNT] shouldBe dropped.toLong()
                    // The first key past the cap — the one a loop bug's author wants to see.
                    snap[CanonicalFields.DROPPED_FIELD_KEY] shouldBe "item_$cap"
                } else {
                    snap shouldNotContainKey CanonicalFields.DROPPED_FIELD_COUNT
                    snap shouldNotContainKey CanonicalFields.DROPPED_FIELD_KEY
                }
            }
        }

        it("applies to increments that would create a new key") {
            val ctx = context(maxFields = 2)
            ctx.increment("a")
            ctx.increment("b")
            ctx.increment("c")

            val snap = ctx.snapshot()
            snap shouldNotContainKey "c"
            snap[CanonicalFields.DROPPED_FIELD_COUNT] shouldBe 1L
            snap[CanonicalFields.DROPPED_FIELD_KEY] shouldBe "c"
        }

        it("still lets existing keys be overwritten and incremented once full") {
            val ctx = context(maxFields = 2)
            ctx.put("a", "first")
            ctx.increment("b")
            ctx.put("a", "second")
            ctx.increment("b", 4L)

            val snap = ctx.snapshot()
            snap["a"] shouldBe "second"
            snap["b"] shouldBe 5L
            snap shouldNotContainKey CanonicalFields.DROPPED_FIELD_COUNT
        }

        it("never drops library-defined fields, so a runaway loop can't cost the line its outcome") {
            val ctx = context(maxFields = 3)
            repeat(10) { ctx.put("loop_$it", it.toLong()) }
            ctx.markFailed("boom")
            ctx.increment(CanonicalFields.DB_QUERY_COUNT)

            val snap = ctx.snapshot()
            snap[CanonicalFields.ERROR] shouldBe true
            snap[CanonicalFields.ERROR_REASON] shouldBe "boom"
            snap[CanonicalFields.DB_QUERY_COUNT] shouldBe 1L
            snap[CanonicalFields.DROPPED_FIELD_COUNT] shouldBe 7L
        }

        it("exempts the adapter's enrich, which writes the line's authoritative fields") {
            val adapter = object : WorkUnitAdapter<Unit> {
                override fun describe(input: Unit) = WorkUnit("id", "test", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: Unit, outcome: Outcome) {
                    ctx.put("adapter_field", "kept")
                }
            }
            val previous = CanonicalLog.limits
            CanonicalLog.limits = CanonicalLogLimits(maxFields = 2)
            var line: Map<String, Any> = emptyMap()
            try {
                withCanonicalLogBlocking(adapter, Unit, emit = { line = it.snapshot() }) {
                    repeat(5) { CanonicalLog.put("handler_$it", it.toLong()) }
                }
            } finally {
                CanonicalLog.limits = previous
            }

            line["adapter_field"] shouldBe "kept"
            line shouldNotContainKey "handler_4"
            // enrich's exemption ends with enrich: dropped handler writes are still counted.
            line[CanonicalFields.DROPPED_FIELD_COUNT] shouldBe 3L
        }

        it("reads CanonicalLog.limits when the context is created, not on each write") {
            val previous = CanonicalLog.limits
            try {
                CanonicalLog.limits = CanonicalLogLimits(maxFields = 1)
                val ctx = CanonicalLogContext(WorkUnit("id", "test", Instant.now()))
                CanonicalLog.limits = CanonicalLogLimits.UNLIMITED
                ctx.put("a", 1L)
                ctx.put("b", 2L)

                ctx.snapshot() shouldNotContainKey "b"
            } finally {
                CanonicalLog.limits = previous
            }
        }
    }

    describe("value length") {
        it("stores an over-long string as its prefix and counts it, for any length") {
            checkAll(Arb.int(1..64), Arb.string(0..200)) { max, value ->
                val ctx = context(maxValueLength = max)
                ctx.put("v", value)

                val snap = ctx.snapshot()
                if (value.length > max) {
                    val stored = snap["v"] as String
                    value.startsWith(stored) shouldBe true
                    // Exactly max, or one less when the cut would split a surrogate pair.
                    (stored.length == max || stored.length == max - 1) shouldBe true
                    snap[CanonicalFields.TRUNCATED_FIELD_COUNT] shouldBe 1L
                    snap[CanonicalFields.TRUNCATED_FIELD_KEY] shouldBe "v"
                } else {
                    snap["v"] shouldBe value
                    snap shouldNotContainKey CanonicalFields.TRUNCATED_FIELD_COUNT
                }
            }
        }

        it("never ends the prefix on half a surrogate pair") {
            val ctx = context(maxValueLength = 3)
            ctx.put("emoji", "ab😀cd") // "ab😀cd": the cut at 3 lands inside the emoji

            ctx.snapshot()["emoji"] shouldBe "ab"
        }

        it("measures any CharSequence, storing the prefix as a String") {
            val ctx = context(maxValueLength = 4)
            ctx.put("builder", StringBuilder("abcdefgh"))

            ctx.snapshot()["builder"] shouldBe "abcd"
        }

        it("leaves non-string values alone and keeps the first truncated key") {
            val ctx = context(maxValueLength = 2)
            ctx.put("n", 123456789L)
            ctx.put("first", "long one")
            ctx.put("second", "another")

            val snap = ctx.snapshot()
            snap["n"] shouldBe 123456789L
            snap[CanonicalFields.TRUNCATED_FIELD_COUNT] shouldBe 2L
            snap[CanonicalFields.TRUNCATED_FIELD_KEY] shouldBe "first"
        }
    }

    describe("CanonicalLogLimits") {
        it("defaults to 256 fields and 8192 characters") {
            CanonicalLogLimits.DEFAULT.maxFields shouldBe 256
            CanonicalLogLimits.DEFAULT.maxValueLength shouldBe 8192
            CanonicalLog.limits shouldBe CanonicalLogLimits.DEFAULT
        }

        it("UNLIMITED changes nothing") {
            val ctx = CanonicalLogContext(WorkUnit("id", "test", Instant.now()), CanonicalLogLimits.UNLIMITED)
            repeat(1000) { ctx.put("k$it", "x".repeat(100)) }

            val snap = ctx.snapshot()
            snap.size shouldBe 1000
            snap.keys.none { it.startsWith("canonical_log_") } shouldBe true
        }

        it("rejects non-positive limits at construction") {
            runCatching { CanonicalLogLimits(maxFields = 0) }.isFailure shouldBe true
            runCatching { CanonicalLogLimits(maxValueLength = -1) }.isFailure shouldBe true
        }
    }
})
