package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLog

/**
 * A contribution the library accepted but could never publish, reported by one of the
 * [CanonicalLog] diagnostic hooks.
 *
 * Two kinds, matching the two ways a contribution goes missing:
 *  - [Unbound] — an ambient `CanonicalLog.put`/`increment`/`markFailed`/`markDegraded` found no
 *    work unit bound on this thread (a plain-executor hop that skipped
 *    `propagatingCanonicalContext()`, a detached coroutine that resumed after the unit closed,
 *    a contribution from inside a sink). The write never happened.
 *  - [LateWrite] — a contributor holding a *captured* context reference wrote into a unit whose
 *    line was already emitted. The write happened; the snapshot was taken before it.
 */
public sealed class LostContribution {
    /** The field key the caller tried to write. */
    public abstract val key: String

    /** No work unit was bound when the ambient contribution ran; nothing was written. */
    public data class Unbound(override val key: String) : LostContribution() {
        override fun toString(): String = "unbound contribution to \"$key\" (no work unit was bound)"
    }

    /** The write landed on [workUnitId]'s accumulator after its line had already been emitted. */
    public data class LateWrite(val workUnitId: String, override val key: String) : LostContribution() {
        override fun toString(): String =
            "late write to \"$key\" on work unit $workUnitId (its canonical line was already emitted)"
    }
}

/** Thrown by the `failOn…` helpers when a contribution is lost inside the guarded block. */
public class LostCanonicalContributionException internal constructor(
    /** The contribution that was lost. */
    public val lost: LostContribution,
) : AssertionError(
    "canonical contribution lost: $lost. " +
        "Wrap the thread hop in propagatingCanonicalContext(), or keep the work unit open " +
        "until the contribution has been made.",
)

/**
 * Run [block] with silently-dropped **ambient** contributions turned into test failures.
 *
 * A propagation bug — an executor hop that skipped `propagatingCanonicalContext()`, a detached
 * coroutine that resumed after the unit closed — degrades to a no-op in production, by design:
 * telemetry must never fail the operation it observes. The cost is that the mistake is only
 * discoverable weeks later as a thinner line on a dashboard. This makes it loud where being
 * loud is free:
 *
 * ```kotlin
 * failOnUnboundContributions {
 *     val line = captureCanonicalLineBlocking { doTheWorkThatHopsThreads() }
 *     line["items_processed"] shouldBe 3L
 * }
 * ```
 *
 * The contribution still no-ops (this changes no library behaviour); the throw surfaces from
 * the thread that made it, so a contribution lost on a pool thread fails *that* task —
 * whether the failure reaches the test depends on whether the test joins it. Prefer asserting
 * on the captured line as well.
 *
 * [CanonicalLog.onUnboundContribution] is process-wide, so this is **not** safe to nest or to
 * run in parallel with another test that installs its own hook (kotest's default
 * single-instance, sequential execution is fine). The previous hook is restored in a `finally`.
 *
 * Use [failOnLateWrites] for the sibling case where the write succeeds but arrives after the
 * line, or [failOnLostContributions] for both at once.
 */
public fun <R> failOnUnboundContributions(block: () -> R): R =
    withHooks(unbound = true, late = false, block = block)

/**
 * Run [block] with **late writes** — contributions made through a captured context reference
 * after that unit's line was emitted — turned into test failures.
 *
 * The motivating shape is an un-awaited `@Async` method or a `GlobalScope.launch` in a service
 * class: invisible at the call site, and the contribution lands on a live map nobody will
 * serialize again. See [failOnUnboundContributions] for the caveats (process-wide hook, throws
 * on the contributing thread).
 */
public fun <R> failOnLateWrites(block: () -> R): R =
    withHooks(unbound = false, late = true, block = block)

/**
 * Run [block] with **both** [failOnUnboundContributions] and [failOnLateWrites] active — the
 * one-liner for "no contribution in this test may be silently lost, whichever way it goes
 * missing".
 */
public fun <R> failOnLostContributions(block: () -> R): R =
    withHooks(unbound = true, late = true, block = block)

private fun <R> withHooks(unbound: Boolean, late: Boolean, block: () -> R): R {
    val previousUnbound = CanonicalLog.onUnboundContribution
    val previousLate = CanonicalLog.onLateWrite
    if (unbound) {
        CanonicalLog.onUnboundContribution = { key ->
            throw LostCanonicalContributionException(LostContribution.Unbound(key))
        }
    }
    if (late) {
        CanonicalLog.onLateWrite = { workUnitId, key ->
            throw LostCanonicalContributionException(LostContribution.LateWrite(workUnitId, key))
        }
    }
    try {
        return block()
    } finally {
        CanonicalLog.onUnboundContribution = previousUnbound
        CanonicalLog.onLateWrite = previousLate
    }
}

/**
 * Run [block] with every lost contribution *recorded* rather than thrown, and hand the list to
 * the caller. The counterpart to [failOnLostContributions] for cases where the contribution
 * happens on a thread the test doesn't join (so a throw would be swallowed by the pool), or
 * where the assertion is "exactly these two are lost".
 *
 * ```kotlin
 * val lost = recordLostContributions { runTheHandler() }
 * lost.shouldBeEmpty()
 * ```
 *
 * The returned list is a copy taken after [block] returns; contributions arriving later are
 * not in it. Same process-wide-hook caveat as [failOnLostContributions].
 */
public fun recordLostContributions(block: () -> Unit): List<LostContribution> {
    val recorded = java.util.Collections.synchronizedList(mutableListOf<LostContribution>())
    val previousUnbound = CanonicalLog.onUnboundContribution
    val previousLate = CanonicalLog.onLateWrite
    CanonicalLog.onUnboundContribution = { key -> recorded += LostContribution.Unbound(key) }
    CanonicalLog.onLateWrite = { workUnitId, key -> recorded += LostContribution.LateWrite(workUnitId, key) }
    try {
        block()
    } finally {
        CanonicalLog.onUnboundContribution = previousUnbound
        CanonicalLog.onLateWrite = previousLate
    }
    return synchronized(recorded) { recorded.toList() }
}
