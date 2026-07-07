package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.CanonicalWorkUnitScope
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import io.github.alexhumphreys.canonicallog.openCanonicalWorkUnit
import io.github.alexhumphreys.canonicallog.withCanonicalCoroutineContext
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compiles and pins `docs/recipes/message-consumers.md`. The adapter, closure loop,
 * split-callback listener, and suspend bridge below are lifted near-verbatim from the recipe;
 * when the two drift, this test is the spec.
 *
 * Lives in `canonical-log-test` (not core) because it exercises the recipe from an *adopter's*
 * seat — the same seat the test kit occupies — and asserting against the emitted snapshot is
 * exactly what the kit exists for.
 */
class MessageConsumerRecipeTest : DescribeSpec({

    describe("closure-shaped poll loop (recipe section 2 + 3)") {
        it("emits one line per message with messaging_* fields, and a marked DLQ failure") {
            val adapter = MessageWorkUnitAdapter()
            val lines = mutableListOf<Map<String, Any>>()
            val writer = CanonicalLineWriter { lines += it.snapshot() }

            val envelopes = listOf(
                Envelope(id = "m-1", source = "orders", payload = "ok"),
                Envelope(id = "m-2", source = "orders", payload = "poison"),
                Envelope(id = "m-3", source = "orders", payload = "ok"),
            )

            for (envelope in envelopes) {
                withCanonicalLogBlocking(adapter, envelope, writer::write) {
                    if (envelope.payload == "poison") {
                        // Business dead-lettering: mark, don't throw.
                        CanonicalLog.markFailed("dead_lettered", "delivery_attempt" to 5L)
                    } else {
                        CanonicalLog.put("order_id", envelope.id)
                    }
                }
            }

            lines shouldHaveSize 3
            currentCanonicalContext().shouldBeNull()

            // Every line carries the mechanically-uniform messaging fields.
            lines.forEach { line ->
                line["work_unit_kind"] shouldBe "message"
                line["messaging_system"] shouldBe "generic"
                line["messaging_destination_name"] shouldBe "orders"
            }
            lines.map { it["messaging_message_id"] } shouldBe listOf("m-1", "m-2", "m-3")
            lines.map { it["work_unit_id"] } shouldBe listOf("m-1", "m-2", "m-3")

            // The marked-failure line: error=true / error_reason=dead_lettered, and NO error_class
            // (the marked-failure signature — a business failure, not an uncaught exception).
            val dlq = lines.single { it["messaging_message_id"] == "m-2" }
            dlq["error"] shouldBe true
            dlq["error_reason"] shouldBe "dead_lettered"
            dlq["delivery_attempt"] shouldBe 5L
            dlq shouldNotContainKey "error_class"

            // Success lines carry no error markers at all.
            val ok = lines.single { it["messaging_message_id"] == "m-1" }
            ok shouldNotContainKey "error"
            ok["order_id"] shouldBe "m-1"
        }
    }

    describe("split-callback listener (recipe section 4)") {
        it("emits once even if the terminal callback fires twice, lands mid-flight contributions, unbinds on onMessage") {
            val listener = CanonicalMessageListener()
            val envelope = Envelope(id = "s-1", source = "events", payload = "ok")

            listener.onMessage(envelope)
            // Threadlocal must be clean once onMessage returns (invariant 1: unbind in finally
            // on the receiving thread), even though the line hasn't been emitted yet.
            currentCanonicalContext().shouldBeNull()
            listener.lines.shouldHaveSize(0)

            // Terminal callback fires twice (containers do this): still exactly one line.
            listener.onAck(envelope)
            listener.onAck(envelope)

            listener.lines shouldHaveSize 1
            val line = listener.lines.single()
            line["messaging_message_id"] shouldBe "s-1"
            line["work_unit_kind"] shouldBe "message"
            // Contribution made between open and ack landed in the snapshot.
            line["processed_count"] shouldBe 1L
            line shouldNotContainKey "error"
        }
    }

    describe("suspend hand-off (recipe section 5)") {
        it("bridges a blocking-opened unit into a suspend body via withCanonicalCoroutineContext") {
            val adapter = MessageWorkUnitAdapter()
            val lines = mutableListOf<Map<String, Any>>()
            val writer = CanonicalLineWriter { lines += it.snapshot() }
            val envelope = Envelope(id = "c-1", source = "jobs", payload = "ok")

            val scope = openCanonicalWorkUnit(adapter, envelope)
            try {
                runBlocking {
                    withCanonicalCoroutineContext {
                        withContext(Dispatchers.Default) {
                            // Landed despite the dispatcher switch off the opening thread.
                            CanonicalLog.increment("io_step_count")
                        }
                    }
                }
            } finally {
                scope.unbind()
            }
            val outcome = scope.outcomeFor(null)
            scope.enrich(adapter, envelope, outcome)
            scope.emit(writer::write)

            currentCanonicalContext().shouldBeNull()
            lines shouldHaveSize 1
            val line = lines.single()
            line["messaging_message_id"] shouldBe "c-1"
            line["io_step_count"] shouldBe 1L
        }
    }
})

// --- Lifted from docs/recipes/message-consumers.md ---

private data class Envelope(
    val id: String,
    val source: String,
    val payload: String,
)

private class MessageWorkUnitAdapter : WorkUnitAdapter<Envelope> {

    override fun describe(input: Envelope): WorkUnit =
        WorkUnit(id = input.id, kind = "message", startedAt = Instant.now())

    override fun enrich(ctx: CanonicalLogContext, input: Envelope, outcome: Outcome) {
        ctx.put("work_unit_id", ctx.workUnit.id)
        ctx.put("work_unit_kind", ctx.workUnit.kind)

        // OTel messaging semconv, dot -> underscore. String literals on purpose: no messaging
        // contributor ships yet, so these are not CanonicalFields constants (field-constants gotcha).
        ctx.put("messaging_system", "generic")
        ctx.put("messaging_destination_name", input.source)
        ctx.put("messaging_message_id", input.id)
        ctx.put("messaging_process_duration_ms", outcome.durationMs)

        if (outcome is Outcome.Threw) {
            ctx.put("error", true)
            ctx.put("error_class", outcome.cause::class.qualifiedName ?: "unknown")
            if (ctx.snapshot()["error_reason"] == null) {
                ctx.put("error_reason", "exception")
            }
        }
    }
}

private class CanonicalMessageListener(
    private val adapter: WorkUnitAdapter<Envelope> = MessageWorkUnitAdapter(),
) {
    val lines = mutableListOf<Map<String, Any>>()
    private val writer = CanonicalLineWriter { lines += it.snapshot() }
    private val scopes = ConcurrentHashMap<String, ScopeState>()

    private class ScopeState(val scope: CanonicalWorkUnitScope, val envelope: Envelope) {
        val finalized = AtomicBoolean(false)
    }

    // Invariant 1: unbind() exactly once, in finally, on the receiving thread.
    fun onMessage(envelope: Envelope) {
        val scope = openCanonicalWorkUnit(adapter, envelope)
        scopes[envelope.id] = ScopeState(scope, envelope)
        try {
            CanonicalLog.increment("processed_count")
        } finally {
            scope.unbind()
        }
    }

    fun onAck(envelope: Envelope) = finalize(envelope, error = null)

    // Invariants 2 + 3: enrich before emit, each at most once; guard the race with AtomicBoolean.
    private fun finalize(envelope: Envelope, error: Throwable?) {
        val state = scopes[envelope.id] ?: return
        if (!state.finalized.compareAndSet(false, true)) return
        scopes.remove(envelope.id)
        val outcome = state.scope.outcomeFor(error)
        state.scope.enrich(adapter, state.envelope, outcome)
        state.scope.emit(writer::write)
    }
}
