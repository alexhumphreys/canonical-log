package io.github.alexhumphreys.canonicallog.otel

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.withCanonicalLog
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import java.time.Instant

/**
 * Pins `OtelSeedingAdapter`: valid trace/span ids captured from `Span.current()` at seed time,
 * neither field emitted when no span is active (never the all-zeroes sentinel), and — the async
 * pin — ids captured on the *opening thread* even when the block switches dispatchers and the
 * span ends right after open. That last case fails if the logic ever moves into `enrich`.
 */
class OtelSeedingAdapterTest : DescribeSpec({

    // Contributes nothing of its own, so the captured snapshot is purely the adapter's writes.
    class NoopAdapter : WorkUnitAdapter<Unit> {
        override fun describe(input: Unit): WorkUnit = WorkUnit("id", "test", Instant.now())
        override fun enrich(ctx: CanonicalLogContext, input: Unit, outcome: Outcome) {}
    }

    val tracerProvider = SdkTracerProvider.builder().build()
    val tracer = tracerProvider.get("canonical-log-tracing-otel-test")

    fun runUnit(block: (CanonicalLogContext) -> Unit = {}): Map<String, Any> {
        var fields: Map<String, Any> = emptyMap()
        withCanonicalLogBlocking(
            OtelSeedingAdapter(NoopAdapter()),
            Unit,
            emit = { fields = it.snapshot() },
            block = block,
        )
        return fields
    }

    describe("OtelSeedingAdapter") {
        it("captures the current span's trace_id and span_id") {
            val span = tracer.spanBuilder("op").startSpan()
            val scope = span.makeCurrent()
            val expectedTrace = span.spanContext.traceId
            val expectedSpan = span.spanContext.spanId
            val fields = try {
                runUnit()
            } finally {
                scope.close()
                span.end()
            }

            fields[CanonicalFields.TRACE_ID] shouldBe expectedTrace
            fields[CanonicalFields.SPAN_ID] shouldBe expectedSpan
        }

        it("emits neither field when no span is active (never the all-zeroes sentinel)") {
            val fields = runUnit()

            fields shouldNotContainKey CanonicalFields.TRACE_ID
            fields shouldNotContainKey CanonicalFields.SPAN_ID
        }

        // The async pin: seed runs synchronously on the opening thread, before the block's
        // dispatcher switch, so the ids are captured even though the span is ended and the
        // OTel context is gone by the time the block (and enrich) run on Dispatchers.IO.
        it("captures ids at seed time even when the block switches dispatchers and the span ends") {
            val span = tracer.spanBuilder("async-op").startSpan()
            val scope = span.makeCurrent()
            val expectedTrace = span.spanContext.traceId
            val expectedSpan = span.spanContext.spanId

            var fields: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(
                    OtelSeedingAdapter(NoopAdapter()),
                    Unit,
                    emit = { fields = it.snapshot() },
                ) {
                    // End the span and leave its scope right after open — before any work runs.
                    scope.close()
                    span.end()
                    // Hop to a thread with no OTel context; an enrich-time read would see nothing.
                    withContext(Dispatchers.IO) {
                        Thread.currentThread().name // touch the IO thread
                    }
                }
            }

            fields[CanonicalFields.TRACE_ID] shouldBe expectedTrace
            fields[CanonicalFields.SPAN_ID] shouldBe expectedSpan
        }
    }
})
