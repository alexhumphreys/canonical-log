package io.github.alexhumphreys.canonicallog

/**
 * Ambient API for contributing fields to the active canonical work unit.
 *
 * All functions are no-ops if no work unit is open on the current thread —
 * safe to call from anywhere (including code paths that don't have a work unit,
 * such as app startup or unit tests that don't open one).
 *
 * The blocking variants work uniformly from synchronous code, virtual threads,
 * and coroutines: the bridge ([CanonicalLogElement]) keeps the threadlocal
 * pointing at the right context across dispatcher switches. There is no need
 * for `suspend` variants — pinned by `BridgeContractTest`.
 *
 * Callable from Java as plain statics (`CanonicalLog.put(...)`, no `INSTANCE`);
 * the `Map` overloads of [markFailed]/[markDegraded] exist so Java callers don't
 * have to construct `kotlin.Pair`s. Pinned by `JavaErgonomicsTest`.
 */
public object CanonicalLog {
    @JvmStatic
    public fun put(key: String, value: Any?) {
        threadLocalContext.get()?.put(key, value)
    }

    @JvmStatic
    @JvmOverloads
    public fun increment(key: String, by: Long = 1L) {
        threadLocalContext.get()?.increment(key, by)
    }

    @JvmStatic
    public fun markFailed(reason: String, vararg extras: Pair<String, Any?>) {
        threadLocalContext.get()?.markFailed(reason, *extras)
    }

    /** Java-friendly overload of [markFailed]; extras semantics are identical. */
    @JvmStatic
    public fun markFailed(reason: String, extras: Map<String, Any?>) {
        threadLocalContext.get()?.markFailed(reason, extras)
    }

    @JvmStatic
    public fun markDegraded(reason: String, vararg extras: Pair<String, Any?>) {
        threadLocalContext.get()?.markDegraded(reason, *extras)
    }

    /** Java-friendly overload of [markDegraded]; extras semantics are identical. */
    @JvmStatic
    public fun markDegraded(reason: String, extras: Map<String, Any?>) {
        threadLocalContext.get()?.markDegraded(reason, extras)
    }
}
