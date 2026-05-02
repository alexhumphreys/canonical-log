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
        fields.merge(key, by) { existing, addition ->
            (existing as Long) + (addition as Long)
        }
    }

    /**
     * Mark this work unit as failed. Sets `error=true` and `error_reason=<reason>`,
     * plus any extra fields the caller supplies. Idempotent — last call wins.
     */
    public fun markFailed(reason: String, vararg fields: Pair<String, Any>) {
        put("error", true)
        put("error_reason", reason)
        fields.forEach { (k, v) -> put(k, v) }
    }

    /**
     * Mark this work unit as degraded — succeeded but with caveats. Sets
     * `degraded=true` and `degraded_reason=<reason>`, plus any extra fields.
     * Does not set `error`.
     */
    public fun markDegraded(reason: String, vararg fields: Pair<String, Any>) {
        put("degraded", true)
        put("degraded_reason", reason)
        fields.forEach { (k, v) -> put(k, v) }
    }

    public fun snapshot(): Map<String, Any> = HashMap(fields)
}
