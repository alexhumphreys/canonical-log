package io.github.alexhumphreys.canonicallog.otel

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.opentelemetry.api.trace.Span

/**
 * A [WorkUnitAdapter] decorator that stamps the active OpenTelemetry trace/span ids onto the
 * canonical line — the OTel-API flavour of the seeding idiom documented on
 * [WorkUnitAdapter.seed] (the MDC flavour is `MdcSeedingAdapter` in core).
 *
 * Wrap any adapter. In [seed] — after the wrapped adapter's own [seed], at work-unit *open*
 * on the *opening thread* — it reads `Span.current().spanContext` and, **only when the context
 * is valid**, puts [CanonicalFields.TRACE_ID] / [CanonicalFields.SPAN_ID]. An absent or invalid
 * span contributes neither field: the OTel all-zeroes sentinel ids
 * (`00000000000000000000000000000000` / `0000000000000000`) are never emitted.
 *
 * **Why `seed` and not `enrich`.** The current span is ambient, request-scoped state that only
 * exists on the opening thread at the start of the unit. By `enrich` time the span may be ended
 * and the thread may be a servlet `AsyncListener` or a scheduling `onStop` thread with no current
 * span — reading it there yields intermittently-empty ids. `seed` runs early enough, on the right
 * thread, to capture it reliably. Reading `Span.current()` in `enrich` is the bug this avoids.
 *
 * Worked example — trace correlation on the HTTP line, keeping the reference adapter's fields:
 * ```
 * val adapter = OtelSeedingAdapter(HttpWorkUnitAdapter(springRouteResolver))
 * ```
 *
 * **Deliberately minimal.** This reads two ids and leaves — no span *creation*, no baggage
 * reading, no OTel context manipulation. Baggage-to-fields is a cardinality/policy decision for
 * the operator and belongs in an adopter-side `seed`, not here.
 *
 * OTel is a `compileOnly` dependency of this module: it touches only `Span.current()` /
 * `SpanContext`, stable since OTel 1.0, so the adopter's own OpenTelemetry version wins at
 * runtime.
 */
public class OtelSeedingAdapter<T>(
    private val delegate: WorkUnitAdapter<T>,
) : WorkUnitAdapter<T> by delegate {
    override fun seed(ctx: CanonicalLogContext, input: T) {
        delegate.seed(ctx, input)
        val spanContext = Span.current().spanContext
        if (spanContext.isValid) {
            ctx.put(CanonicalFields.TRACE_ID, spanContext.traceId)
            ctx.put(CanonicalFields.SPAN_ID, spanContext.spanId)
        }
    }
}
