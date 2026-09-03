package io.github.alexhumphreys.canonicallog

import java.util.concurrent.ConcurrentHashMap

public class CanonicalLogContext @DelicateCanonicalLogApi public constructor(
    public val workUnit: WorkUnit,
) {
    internal val fields: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    public fun put(key: String, value: Any?) {
        if (value == null) return
        fields[key] = value
    }

    /**
     * Add [by] to the Long counter at [key], creating it if absent.
     *
     * A field that's incremented must only ever be written via `increment()`, never
     * [put]. If [key] already holds a non-Long (someone `put` it), the increment is
     * **dropped** and the conflict is recorded on the canonical line instead, via
     * `canonical_log_type_conflict=true` and `canonical_log_type_conflict_key=<key>`
     * (last conflicting key wins). Deliberately non-throwing: increments are called
     * from contributors embedded in application-critical paths (JDBC listeners,
     * HTTP interceptors), and telemetry must never fail the operation it observes —
     * a throw here would fail the app's actual DB call or replace the real
     * `IOException` of a failed HTTP call.
     *
     * **Conflict markers are best-effort diagnostics, not linearizable state.** The
     * conflict is detected inside `merge`, but the three marker fields are written
     * *after* `merge` returns, so under concurrency they trail the increment's
     * linearization point: a snapshot may briefly see the dropped increment's effect
     * before the markers, racing conflicts may interleave their `_key`/`_type` writes
     * (each field is last-writer-wins), and the flag can survive on a line whose field
     * was later `put` back to a healthy Long — the flag records that *some* increment
     * was dropped, not that the field's final value is wrong. The increment/put field
     * *values* themselves are linearizable (`CanonicalLogContextLincheckTest` model
     * checks this and the quiescent marker coherence exhaustively within bounds).
     */
    @JvmOverloads
    public fun increment(key: String, by: Long = 1L) {
        // The remapping function is the stateless singleton [SumLongs] rather than a lambda,
        // and the conflict is read off merge's *return* value rather than out of a captured
        // local. A lambda that captured a `var` would allocate two objects on every increment
        // (the lambda instance plus a Ref.ObjectRef for the capture) on a path adopters call
        // many times per work unit; this way the only allocation left is the boxed Long
        // result, which the Map<String, Any> accumulator makes unavoidable. Pinned by
        // AllocationBudgetTest.
        //
        // merge returns the value now stored under [key]: the summed Long on the happy path,
        // or — when a non-Long was already there and SumLongs declined to touch it — that
        // same non-Long. So "not a Long" is exactly the type-conflict signal, and the value
        // it returns is the conflicting one whose type gets reported.
        val merged = fields.merge(key, by, SumLongs)
        if (merged !is Long) {
            put(CanonicalFields.TYPE_CONFLICT, true)
            put(CanonicalFields.TYPE_CONFLICT_KEY, key)
            put(CanonicalFields.TYPE_CONFLICT_TYPE, merged?.let { it::class.qualifiedName } ?: "unknown")
        }
    }

    /**
     * Mark this work unit as failed. Sets `error=true` and `error_reason=<reason>`,
     * plus any extra fields the caller supplies. Null-valued extras are dropped,
     * matching [put]. Idempotent — last call wins.
     *
     * **Not an atomic pair.** `error` and `error_reason` are two independent [put]s;
     * each field is individually last-writer-wins. A reader racing the mark (a
     * concurrent [snapshot]) can observe `error=true` before `error_reason` lands —
     * no write ordering removes the window, it only mirrors the tear
     * (`CanonicalLogContextLincheckTest`, weakening 3). Emit-time snapshots are taken
     * after the work unit completes and always see both fields.
     */
    public fun markFailed(reason: String, vararg extras: Pair<String, Any?>) {
        put(CanonicalFields.ERROR, true)
        put(CanonicalFields.ERROR_REASON, reason)
        extras.forEach { (k, v) -> put(k, v) }
    }

    /** Java-friendly overload of [markFailed]; extras semantics are identical. */
    public fun markFailed(reason: String, extras: Map<String, Any?>) {
        put(CanonicalFields.ERROR, true)
        put(CanonicalFields.ERROR_REASON, reason)
        extras.forEach { (k, v) -> put(k, v) }
    }

    /**
     * Mark this work unit as degraded — succeeded but with caveats. Sets
     * `degraded=true` and `degraded_reason=<reason>`, plus any extra fields.
     * Null-valued extras are dropped. Does not set `error`.
     *
     * Like [markFailed], the `degraded`/`degraded_reason` pair is **not atomic** —
     * see the note there.
     */
    public fun markDegraded(reason: String, vararg extras: Pair<String, Any?>) {
        put(CanonicalFields.DEGRADED, true)
        put(CanonicalFields.DEGRADED_REASON, reason)
        extras.forEach { (k, v) -> put(k, v) }
    }

    /** Java-friendly overload of [markDegraded]; extras semantics are identical. */
    public fun markDegraded(reason: String, extras: Map<String, Any?>) {
        put(CanonicalFields.DEGRADED, true)
        put(CanonicalFields.DEGRADED_REASON, reason)
        extras.forEach { (k, v) -> put(k, v) }
    }

    /**
     * The current value at [key], or `null` if absent.
     *
     * The cheap single-key read behind the check-before-default pattern an adapter's
     * `enrich` uses for handler-ownable fields (`ctx.get(CanonicalFields.ERROR_REASON) == null`)
     * — same result as `snapshot()[key]` without copying the whole accumulator.
     *
     * Same consistency as [snapshot]: an individual field read is linearizable — never torn,
     * never a lost increment (pinned by `CanonicalLogContextLincheckTest`). There is still no
     * atomicity *across* keys, so two `get`s racing a [markFailed] can see `error=true` and
     * `error_reason=null`, exactly as a snapshot can.
     */
    public fun get(key: String): Any? = fields[key]

    /**
     * True if [key] currently holds a value. Same per-key linearizability and same
     * across-key weakenings as [get]/[snapshot].
     */
    public fun contains(key: String): Boolean = fields.containsKey(key)

    /**
     * Return a defensive copy of the current fields. The copy is shallow: mutable
     * values stored via [put] (e.g. lists) are shared by reference. In practice the
     * values are primitives, strings, or otherwise immutable, so this is a non-issue —
     * but if a caller stores a mutable value and mutates it after `snapshot()`, the
     * mutation is visible through the snapshot.
     *
     * The copy is **weakly consistent**, not point-atomic: it iterates the underlying
     * `ConcurrentHashMap` while writers may still be active, so a snapshot taken
     * mid-flight can observe one field of a racing multi-field write (e.g. [markFailed]'s
     * `error`) and miss another (`error_reason`). Each *individual* field read through
     * the snapshot is linearizable — never torn, never a lost increment (pinned by
     * `CanonicalLogContextLincheckTest`). Real usage doesn't rely on more: emit takes
     * the snapshot after the work unit completes, and contributions racing emit are
     * documented best-effort cutoff.
     */
    public fun snapshot(): Map<String, Any> {
        // Not HashMap(fields): the copy constructor consults ConcurrentHashMap.size(),
        // which can read 0 while entries exist (a racing put has inserted its node but
        // not yet bumped the count), making HashMap skip iteration and return an empty
        // copy — losing fields this thread itself already wrote. Iterating never
        // depends on the count. Lincheck-derived; pinned by CanonicalLogContextLincheckTest.
        val copy = HashMap<String, Any>()
        fields.forEach { (k, v) -> copy[k] = v }
        return copy
    }
}

/**
 * `ConcurrentHashMap.merge` remapping function for [CanonicalLogContext.increment]: sums two
 * Longs, and leaves a non-Long value untouched so the caller can detect the type conflict from
 * the returned value. Deliberately a stateless singleton — see the comment in `increment`.
 */
private object SumLongs : java.util.function.BiFunction<Any, Any, Any> {
    override fun apply(existing: Any, incoming: Any): Any =
        if (existing is Long && incoming is Long) existing + incoming else existing
}
