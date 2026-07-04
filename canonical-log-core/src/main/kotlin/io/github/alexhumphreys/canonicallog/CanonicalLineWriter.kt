package io.github.alexhumphreys.canonicallog

/**
 * The named injectable seam an integration hands the finalized work unit to — the sink
 * that turns a canonical log context into an emitted line.
 *
 * **`write` is expected not to throw.** By the time it runs the work unit is already
 * finalized (handler done, adapter enriched, threadlocal restored), so a throwing writer
 * is a wiring bug at the sink level. But telemetry must never fail the operation it
 * observes: if `write` throws an [Exception] anyway, the caller catches it, WARN-logs it
 * to the `io.github.alexhumphreys.canonicallog` logger, and the operation completes as
 * normal. The canonical line is dropped; the WARN is the only record. Keep implementations
 * dead simple.
 *
 * Register/provide an implementation to route canonical lines somewhere other than the
 * default sink (log4j2, plain JSON, Kafka, ...). The Spring auto-configuration resolves
 * beans of this type and injects them into the HTTP filter and the scheduling handler.
 *
 * Relationship to [EmitFn]: [EmitFn] stays the raw lambda primitive the lifecycle takes;
 * [CanonicalLineWriter] is the named injectable seam. Entry points call
 * `scope.emit(writer::write)`. Neither is deprecated.
 */
public fun interface CanonicalLineWriter {
    public fun write(context: CanonicalLogContext)
}
