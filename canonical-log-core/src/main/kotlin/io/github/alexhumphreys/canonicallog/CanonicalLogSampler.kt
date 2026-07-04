package io.github.alexhumphreys.canonicallog

/**
 * Decides, per work unit, whether the finalized canonical line is written.
 *
 * Consulted *after* enrichment, so the predicate sees the complete line — status,
 * duration, error fields, handler-supplied fields. That makes outcome-biased
 * sampling the natural shape: always keep failures, sample healthy traffic:
 *
 * ```kotlin
 * CanonicalLogSampler { ctx -> ctx.snapshot()["error"] == true || Random.nextDouble() < 0.01 }
 * ```
 *
 * Register/provide an implementation and the integration wires it into its sink path; the
 * default emits every line. The Spring auto-configuration resolves a bean of this type. To
 * skip requests without doing any of the work-unit bookkeeping (e.g. health probes), prefer
 * an integration-level path exclusion — the sampler runs after the request has already paid
 * for context creation and enrichment.
 *
 * A throwing sampler fails open: the exception is caught and WARN-logged to the
 * `io.github.alexhumphreys.canonicallog` logger, the line is still written, and
 * the operation is unaffected.
 */
public fun interface CanonicalLogSampler {
    public fun shouldEmit(context: CanonicalLogContext): Boolean
}
