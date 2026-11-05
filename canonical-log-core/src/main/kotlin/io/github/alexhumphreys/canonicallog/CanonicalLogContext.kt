package io.github.alexhumphreys.canonicallog

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

public class CanonicalLogContext @JvmOverloads @DelicateCanonicalLogApi public constructor(
    public val workUnit: WorkUnit,
    limits: CanonicalLogLimits = CanonicalLog.limits,
) {
    internal val fields: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    // Copied out of [limits] so the hot path reads two final ints, not a volatile and a field.
    private val maxFields = limits.maxFields
    private val maxValueLength = limits.maxValueLength

    /**
     * True while the adapter's `enrich` runs (set by `runEnrich`). New keys written then are
     * exempt from the field cap: enrich writes the line's authoritative fields (status, outcome,
     * error defaults) once per unit, and a runaway loop in the handler must not cost us those.
     */
    @Volatile
    internal var enriching: Boolean = false

    /**
     * Set once the line has been published (by `safeEmit`, after the writer took its snapshot).
     * Writes arriving afterwards still succeed — the map is a valid object and refusing the
     * write buys nothing while risking a surprise mid-teardown — but they are lost, so they are
     * counted and warned about instead of vanishing. Read on the write path only.
     */
    @Volatile
    internal var finalized: Boolean = false

    /** One WARN per context, however many late writes arrive. */
    private val lateWriteWarned = AtomicBoolean(false)

    /**
     * Set [key] to [value]; a `null` value is dropped. Subject to [CanonicalLogLimits]: a new key
     * past the field cap is dropped, and a `CharSequence` longer than the value limit is stored
     * truncated. Both are counted on the line (`canonical_log_dropped_field_*` /
     * `canonical_log_truncated_field_*`), never thrown.
     */
    public fun put(key: String, value: Any?) {
        if (value == null) return
        if (finalized) recordLateWrite(key)
        // size() reads ConcurrentHashMap's counter without allocating; the slower admission
        // checks only run once the map is already full.
        if (fields.size >= maxFields && !admitsNewKey(key)) return
        fields[key] = if (value is CharSequence && value.length > maxValueLength) truncate(key, value) else value
    }

    /**
     * Over the field cap: may [key] still be written? Yes if it's an overwrite, if the adapter's
     * enrich is running, or if it's a field the library itself defines. Otherwise the write is
     * dropped and counted.
     */
    private fun admitsNewKey(key: String): Boolean {
        if (fields.containsKey(key) || enriching || key in libraryFieldNames) return true
        // Raw writes, not put()/increment(): the markers report on the cap and must not be
        // subject to it (or counted as late writes).
        fields.merge(CanonicalFields.DROPPED_FIELD_COUNT, 1L, SumLongs)
        fields.putIfAbsent(CanonicalFields.DROPPED_FIELD_KEY, key)
        return false
    }

    private fun truncate(key: String, value: CharSequence): String {
        var end = maxValueLength
        // Don't leave half a surrogate pair at the end of the prefix.
        if (Character.isHighSurrogate(value[end - 1])) end -= 1
        fields.merge(CanonicalFields.TRUNCATED_FIELD_COUNT, 1L, SumLongs)
        fields.putIfAbsent(CanonicalFields.TRUNCATED_FIELD_KEY, key)
        return value.subSequence(0, end).toString()
    }

    /**
     * Report a write that arrived after the line was emitted: count it on the (now pointless)
     * map, WARN once, and notify [CanonicalLog.onLateWrite] if a diagnostic hook is installed.
     *
     * The counter lands on a map nobody will serialize again — that is the honest limit here.
     * The operator-facing signal is the WARN; the test-facing one is the hook (see
     * `canonical-log-test`'s `failOnLateWrites`). Deliberately no attempt to re-emit or amend
     * the line: a second line for the same work unit is worse than a missing field.
     */
    private fun recordLateWrite(key: String) {
        // Raw write, not increment(): the counter is itself a post-finalize write, and routing
        // it back through put/increment would recurse.
        fields.merge(CanonicalFields.LATE_WRITE_COUNT, 1L) { existing, _ ->
            if (existing is Long) existing + 1L else existing
        }
        if (lateWriteWarned.compareAndSet(false, true)) {
            libraryLogger.warn(
                "contribution to {} arrived after the canonical line for work unit {} was emitted; " +
                    "it is lost (later late writes are counted in {} but not warned about again)",
                key,
                workUnit.id,
                CanonicalFields.LATE_WRITE_COUNT,
            )
        }
        CanonicalLog.onLateWrite?.invoke(workUnit.id, key)
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
        if (finalized) recordLateWrite(key)
        // A Long can't breach the value-length limit, but creating a new counter key is
        // subject to the field cap like any put.
        if (fields.size >= maxFields && !admitsNewKey(key)) return
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
            // Raw writes, not put(): these markers are the library reporting on the increment
            // above, not fresh contributions — routing them through put() would count one
            // misuse as several late writes once the context is finalized.
            fields[CanonicalFields.TYPE_CONFLICT] = true
            fields[CanonicalFields.TYPE_CONFLICT_KEY] = key
            fields[CanonicalFields.TYPE_CONFLICT_TYPE] = merged?.let { it::class.qualifiedName } ?: "unknown"
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
 * Every field name the library defines — the `String` constants on [CanonicalFields]. Keys in
 * this set bypass the field cap (see [CanonicalLogLimits]): the set is fixed and small, so the
 * exemption keeps the map bounded. Read reflectively once, so a new constant is covered
 * without anyone remembering to list it here.
 */
private val libraryFieldNames: Set<String> =
    CanonicalFields::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }
        .toHashSet()

/**
 * `ConcurrentHashMap.merge` remapping function for [CanonicalLogContext.increment]: sums two
 * Longs, and leaves a non-Long value untouched so the caller can detect the type conflict from
 * the returned value. Deliberately a stateless singleton — see the comment in `increment`.
 */
private object SumLongs : java.util.function.BiFunction<Any, Any, Any> {
    override fun apply(existing: Any, incoming: Any): Any =
        if (existing is Long && incoming is Long) existing + incoming else existing
}
