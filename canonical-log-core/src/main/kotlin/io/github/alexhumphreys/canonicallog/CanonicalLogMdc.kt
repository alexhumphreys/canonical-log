package io.github.alexhumphreys.canonicallog

import org.slf4j.MDC

/**
 * Mirrors the active work unit's id into slf4j [MDC] under the key `work_unit_id` —
 * the same name the canonical line carries — so every ordinary log line written
 * during a work unit can be joined to its canonical line (and vice versa) with a
 * single equality query.
 *
 * The mirror is kept in lockstep with the threadlocal binding by the entry points
 * and propagation helpers:
 *  - [withCanonicalLogBlocking] installs on entry and restores on unwind;
 *  - [withCanonicalLog] gets it via [CanonicalLogElement], which installs/restores
 *    on every coroutine dispatch — so there is no need to pair the suspend entry
 *    point with `kotlinx-coroutines-slf4j`'s `MDCContext` for this key;
 *  - the `propagatingCanonicalContext()` wrappers install/restore around the task
 *    on the worker thread.
 *
 * Nested work units follow the inner-shadows-outer contract: MDC shows the
 * innermost unit's id, and the enclosing unit's id is restored when it closes —
 * always matching where ambient contributions go.
 *
 * [enabled] is a process-wide opt-out for shops that already manage MDC themselves
 * (the Spring starter binds it from `canonical-log.http.mdc-enabled`). Set it once
 * at startup, before any work unit opens: flipping it while work units are in
 * flight can leave a stale `work_unit_id` in MDC or restore-skip a saved value.
 */
public object CanonicalLogMdc {

    /** The MDC key, matching the canonical line's `work_unit_id` field. */
    public const val KEY: String = "work_unit_id"

    /** Process-wide switch; see the class KDoc for the set-at-startup-only caveat. */
    @Volatile
    public var enabled: Boolean = true

    /**
     * Put [context]'s work unit id under [KEY] and return the value it displaced
     * (`null` if the key was unset). Pass the return value to [restore] on unwind —
     * the same previous-value discipline as the threadlocal. No-op returning `null`
     * when [enabled] is false.
     *
     * Entry-point authors pair this with [bindCurrentCanonicalContext], which
     * deliberately does *not* touch MDC itself (it can't carry the previous MDC
     * value through its restore-by-rebinding contract).
     */
    @DelicateCanonicalLogApi
    public fun install(context: CanonicalLogContext): String? {
        if (!enabled) return null
        val previous: String? = MDC.get(KEY)
        MDC.put(KEY, context.workUnit.id)
        return previous
    }

    /** Undo [install]: put back [previous], or remove [KEY] if there was none. */
    @DelicateCanonicalLogApi
    public fun restore(previous: String?) {
        if (!enabled) return
        if (previous == null) MDC.remove(KEY) else MDC.put(KEY, previous)
    }
}
