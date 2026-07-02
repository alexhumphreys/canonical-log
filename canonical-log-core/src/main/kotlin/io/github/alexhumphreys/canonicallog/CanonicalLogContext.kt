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
     */
    public fun increment(key: String, by: Long = 1L) {
        var conflictingType: String? = null
        fields.merge(key, by) { existing, _ ->
            if (existing is Long) {
                existing + by
            } else {
                conflictingType = existing::class.qualifiedName ?: "unknown"
                existing
            }
        }
        if (conflictingType != null) {
            put("canonical_log_type_conflict", true)
            put("canonical_log_type_conflict_key", key)
            put("canonical_log_type_conflict_type", conflictingType)
        }
    }

    /**
     * Mark this work unit as failed. Sets `error=true` and `error_reason=<reason>`,
     * plus any extra fields the caller supplies. Null-valued extras are dropped,
     * matching [put]. Idempotent — last call wins.
     */
    public fun markFailed(reason: String, vararg extras: Pair<String, Any?>) {
        put("error", true)
        put("error_reason", reason)
        extras.forEach { (k, v) -> put(k, v) }
    }

    /**
     * Mark this work unit as degraded — succeeded but with caveats. Sets
     * `degraded=true` and `degraded_reason=<reason>`, plus any extra fields.
     * Null-valued extras are dropped. Does not set `error`.
     */
    public fun markDegraded(reason: String, vararg extras: Pair<String, Any?>) {
        put("degraded", true)
        put("degraded_reason", reason)
        extras.forEach { (k, v) -> put(k, v) }
    }

    /**
     * Return a defensive copy of the current fields. The copy is shallow: mutable
     * values stored via [put] (e.g. lists) are shared by reference. In practice the
     * values are primitives, strings, or otherwise immutable, so this is a non-issue —
     * but if a caller stores a mutable value and mutates it after `snapshot()`, the
     * mutation is visible through the snapshot.
     */
    public fun snapshot(): Map<String, Any> = HashMap(fields)
}
