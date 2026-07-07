package io.github.alexhumphreys.canonicallog

import org.slf4j.MDC

/**
 * A [WorkUnitAdapter] decorator that copies named slf4j MDC entries onto the canonical line
 * at work-unit *open*, on the *opening thread* — the shipped form of the composition idiom
 * documented on [WorkUnitAdapter.seed].
 *
 * Wrap any adapter and hand it a [keys] map of *MDC key → canonical field name*. In [seed]
 * — after the wrapped adapter's own [seed] — it reads each MDC key and [CanonicalLogContext.put]s
 * it under the mapped field name. Because [CanonicalLogContext.put] drops nulls, absent MDC
 * keys contribute nothing: naming a key that isn't set costs nothing and adds no field.
 *
 * **Why `seed` and not `enrich`.** MDC entries such as a tracing agent's `trace_id`/`span_id`
 * or an upstream filter's `request_id` are *ambient, request-scoped state that only exists on
 * the opening thread at the start of the unit*. By `enrich` time the work may have hopped to a
 * servlet `AsyncListener` thread or a scheduling `onStop` thread whose MDC is empty (or worse,
 * carries another request's values) — reading MDC there yields intermittently-empty or bleeding
 * trace ids. `seed` runs early enough, on the right thread, to read it reliably. **Reading MDC
 * in `enrich` is exactly the bug this class exists to prevent.**
 *
 * **Mechanism, not policy.** Agents disagree on MDC key names (`trace_id`, `dd.trace_id`,
 * `traceId`, ...); the library just runs the copy at the right moment and lets the adopter name
 * the keys and the target fields. Renames are free — `"dd.trace_id" to CanonicalFields.TRACE_ID`
 * captures Datadog's MDC key under the canonical field name.
 *
 * Worked example — trace ids from a tracing agent that mirrors them into MDC:
 * ```
 * val adapter = MdcSeedingAdapter(
 *     HttpWorkUnitAdapter(),
 *     mapOf(
 *         "trace_id" to CanonicalFields.TRACE_ID,
 *         "span_id" to CanonicalFields.SPAN_ID,
 *     ),
 * )
 * ```
 *
 * For stacks with the OpenTelemetry API on the classpath, prefer `OtelSeedingAdapter`
 * (canonical-log-tracing-otel), which reads `Span.current()` directly and never emits the
 * all-zeroes sentinel ids. This class is the zero-dependency path for agent/framework stacks
 * that already mirror ids into MDC.
 */
public class MdcSeedingAdapter<T>(
    private val delegate: WorkUnitAdapter<T>,
    private val keys: Map<String, String>,
) : WorkUnitAdapter<T> by delegate {
    override fun seed(ctx: CanonicalLogContext, input: T) {
        delegate.seed(ctx, input)
        keys.forEach { (mdcKey, field) -> ctx.put(field, MDC.get(mdcKey)) }
    }
}
