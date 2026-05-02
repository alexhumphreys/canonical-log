package io.canonlog

/**
 * Ambient API for contributing fields to the active canonical work unit.
 *
 * All four functions are no-ops if no work unit is open on the current thread —
 * safe to call from anywhere (including code paths that don't have a work unit,
 * such as app startup or unit tests that don't open one).
 *
 * The blocking variants work uniformly from synchronous code, virtual threads,
 * and coroutines: the bridge ([CanonicalLogElement]) keeps the threadlocal
 * pointing at the right context across dispatcher switches. There is no need
 * for `suspend` variants — pinned by `BridgeContractTest`.
 */
public object CanonicalLog {
    public fun put(key: String, value: Any?) {
        threadLocalContext.get()?.put(key, value)
    }

    public fun increment(key: String, by: Long = 1L) {
        threadLocalContext.get()?.increment(key, by)
    }

    public fun markFailed(reason: String, vararg extras: Pair<String, Any?>) {
        threadLocalContext.get()?.markFailed(reason, *extras)
    }

    public fun markDegraded(reason: String, vararg extras: Pair<String, Any?>) {
        threadLocalContext.get()?.markDegraded(reason, *extras)
    }
}
