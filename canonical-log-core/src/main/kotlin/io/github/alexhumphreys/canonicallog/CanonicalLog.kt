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
 *
 * **Making the no-ops loud in tests:** the silent no-op is the right production
 * behaviour but hides propagation bugs (a plain-executor hop, a detached coroutine)
 * until someone notices a missing field on a dashboard. [onUnboundContribution] and
 * [onLateWrite] are opt-in diagnostic hooks for exactly that; `canonical-log-test`
 * wraps them as `failOnUnboundContributions { }` / `failOnLateWrites { }` /
 * `failOnLostContributions { }`.
 */
public object CanonicalLog {

    /**
     * Invoked with the offending field key when an ambient contribution finds **no bound work
     * unit** — the contribution is dropped, as always; this only reports it. Default `null`
     * (no reporting), which is the production configuration: the callback is read *only* on
     * the already-unbound branch, so a bound contribution pays nothing for it.
     *
     * Mechanism, not policy: this is a process-wide hook meant to be set once at startup or
     * test setup and left alone (`canonical-log-test`'s `failOnUnboundContributions { }`
     * installs a throwing one and restores in a `finally`). A hook installed in a production
     * config **must not throw** — it runs inside whatever critical path the contributor sits
     * in, and telemetry must never fail the operation it observes.
     *
     * It fires for every ambient entry point ([put], [increment], [markFailed],
     * [markDegraded]) — including contributions made from inside an [EmitFn], which run
     * deliberately unbound at top level (see [EmitFn]; contributing from a sink is
     * discouraged, so reporting it is the point). It does **not** fire for the sibling
     * failure where a contributor holds a *captured* context reference and writes into an
     * already-emitted unit — the write succeeds there and is still lost; that is
     * [onLateWrite].
     */
    @JvmStatic
    @Volatile
    public var onUnboundContribution: ((key: String) -> Unit)? = null

    /**
     * Invoked with the work-unit id and field key when a contribution lands on a context whose
     * line has **already been emitted** (an un-awaited `@Async`, a `propagatingCanonicalContext()`
     * task that outlives its unit, an OkHttp `enqueue()` callback resolving the request tag after
     * the unit ended). The write succeeds against a live map but missed the snapshot, so it is
     * lost. Unlike [onUnboundContribution] this always has a work unit to name; the library also
     * counts these on the context (`canonical_log_late_write_count`) and WARNs once per unit.
     *
     * Default `null`. Same contract as [onUnboundContribution]: set once, must not throw in a
     * production config.
     */
    @JvmStatic
    @Volatile
    public var onLateWrite: ((workUnitId: String, key: String) -> Unit)? = null

    @JvmStatic
    public fun put(key: String, value: Any?) {
        val ctx = threadLocalContext.get()
        if (ctx == null) {
            onUnboundContribution?.invoke(key)
            return
        }
        ctx.put(key, value)
    }

    @JvmStatic
    @JvmOverloads
    public fun increment(key: String, by: Long = 1L) {
        val ctx = threadLocalContext.get()
        if (ctx == null) {
            onUnboundContribution?.invoke(key)
            return
        }
        ctx.increment(key, by)
    }

    @JvmStatic
    public fun markFailed(reason: String, vararg extras: Pair<String, Any?>) {
        val ctx = threadLocalContext.get()
        if (ctx == null) {
            onUnboundContribution?.invoke(CanonicalFields.ERROR)
            return
        }
        ctx.markFailed(reason, *extras)
    }

    /** Java-friendly overload of [markFailed]; extras semantics are identical. */
    @JvmStatic
    public fun markFailed(reason: String, extras: Map<String, Any?>) {
        val ctx = threadLocalContext.get()
        if (ctx == null) {
            onUnboundContribution?.invoke(CanonicalFields.ERROR)
            return
        }
        ctx.markFailed(reason, extras)
    }

    @JvmStatic
    public fun markDegraded(reason: String, vararg extras: Pair<String, Any?>) {
        val ctx = threadLocalContext.get()
        if (ctx == null) {
            onUnboundContribution?.invoke(CanonicalFields.DEGRADED)
            return
        }
        ctx.markDegraded(reason, *extras)
    }

    /** Java-friendly overload of [markDegraded]; extras semantics are identical. */
    @JvmStatic
    public fun markDegraded(reason: String, extras: Map<String, Any?>) {
        val ctx = threadLocalContext.get()
        if (ctx == null) {
            onUnboundContribution?.invoke(CanonicalFields.DEGRADED)
            return
        }
        ctx.markDegraded(reason, extras)
    }
}
