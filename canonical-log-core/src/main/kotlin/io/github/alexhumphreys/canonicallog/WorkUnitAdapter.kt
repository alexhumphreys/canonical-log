package io.github.alexhumphreys.canonicallog

/**
 * Translates between an entry-point's input type and the canonical work-unit lifecycle.
 *
 * One implementation per kind of entry point — HTTP requests, Kafka messages, scheduled
 * jobs, etc. The implementation captures what's mechanically uniform across every
 * invocation of that entry point: a request's method/route/status, a message's
 * topic/partition, and so on. Per-operation values (`post_id`, `comment_count`) are
 * the handler's job, not the adapter's.
 *
 * **Adapters must not throw.** Both [describe] and [enrich] are called by
 * `withCanonicalLog{,Blocking}` as part of the work-unit lifecycle, and a throwing
 * adapter is a bug. The library is defensive against it (a throwing [enrich] records
 * `canonical_log_enrich_error` on the canonical line and logs a WARN; the block's
 * result is never replaced), but treat that as a backstop, not a contract — adapter
 * implementations should read inputs that are guaranteed-valid by their caller and
 * write fields that are guaranteed-formattable.
 */
public interface WorkUnitAdapter<T> {
    /**
     * Build the [WorkUnit] identity for this invocation. Called once at the start of
     * the lifecycle. Should not have side effects beyond reading [input].
     */
    public fun describe(input: T): WorkUnit

    /**
     * Write the adapter's mechanically-uniform fields onto the [ctx]. Called once at
     * the end of the lifecycle, after the body has run. Has access to the original
     * [input] and the [outcome] (lifecycle-level success or thrown exception).
     */
    public fun enrich(ctx: CanonicalLogContext, input: T, outcome: Outcome)
}
