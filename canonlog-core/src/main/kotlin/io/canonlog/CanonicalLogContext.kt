package io.canonlog

import java.util.concurrent.ConcurrentHashMap

public class CanonicalLogContext @DelicateCanonicalLogApi public constructor(
    public val workUnit: WorkUnit,
) {
    internal val fields: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    public fun put(key: String, value: Any?) {
        if (value == null) return
        fields[key] = value
    }

    public fun increment(key: String, by: Long = 1L) {
        fields.merge(key, by) { existing, _ ->
            check(existing is Long) {
                "Cannot increment canonical-log field '$key': existing value has type " +
                    "${existing::class.qualifiedName}, expected Long. A field that's " +
                    "incremented must only ever be written via increment(), never put()."
            }
            existing + by
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
