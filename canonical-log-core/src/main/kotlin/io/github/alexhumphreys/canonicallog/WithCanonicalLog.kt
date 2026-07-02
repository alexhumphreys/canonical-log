package io.github.alexhumphreys.canonicallog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Logger for the library's own failure reporting (a throwing emit or enrich). Named
 * after the package rather than a class so adopters can target it with one config line.
 */
private val libraryLogger = LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog")

/**
 * The hand-off the entry point uses to publish the finalized canonical line.
 *
 * **`emit` is expected not to throw.** By the time it runs, the work unit is already
 * finalized — the block returned (or threw), the adapter's `enrich` ran, and the
 * threadlocal has been restored. A throwing emit is a wiring bug at the entry-point
 * level (the canonical sink itself failed). But telemetry must never fail the
 * operation it observes: if emit throws an [Exception] anyway, the library catches
 * it, logs one WARN to the `io.github.alexhumphreys.canonicallog` slf4j logger
 * (including the work unit id and the exception), and then returns the block's
 * result / rethrows the block's exception as normal. The canonical line is lost;
 * the WARN is the only record. [Error]s still propagate — see the
 * catch-`Exception`-not-`Error` rationale in [withCanonicalLogBlocking].
 */
public typealias EmitFn = (CanonicalLogContext) -> Unit

/**
 * Blocking entry point for opening a canonical work unit.
 *
 * Lifecycle:
 *  1. Build a [CanonicalLogContext] from `adapter.describe(input)`.
 *  2. Install it as the current-thread canonical context.
 *  3. Run [block] with the context.
 *  4. Call `adapter.enrich` exactly once with the resulting [Outcome].
 *  5. Restore the previous threadlocal binding and call [emit] — both always run.
 *  6. Return the block's result, or rethrow its exception.
 *
 * **Nesting — inner shadows outer.** Opening a work unit inside an already-active
 * one is supported: while the inner unit is open, ambient contributions
 * ([CanonicalLog.put] and friends) route to the inner accumulator only; when it
 * closes, the enclosing unit resumes receiving contributions. Each unit emits its
 * own canonical line (inner first), and a nested unit's line carries
 * `parent_work_unit_id` with the immediately enclosing unit's id (each level
 * records only its direct parent — reconstruct deeper chains by joining lines)
 * plus `work_unit_depth` (1 for a unit opened inside a top-level unit, 2 inside
 * that, and so on; omitted on top-level lines — absent means 0). Fields are never
 * aggregated from an inner line into an outer one. The contract is identical for
 * [withCanonicalLogBlocking] and [withCanonicalLog], nested in any combination;
 * pinned by `NestedWorkUnitTest`.
 *
 * **Cancellation:** a block that terminates with a [CancellationException] (e.g. a
 * `Future.get` on a cancelled future, or blocking-over-coroutine bridges) produces
 * [Outcome.Cancelled] rather than [Outcome.Threw], and the exception is rethrown
 * after enrich/emit — cancellation is observed, never swallowed.
 *
 * Calling this inside a coroutine that does dispatcher switches is **undefined**
 * for the inner switches — the threadlocal is set on the entering thread only;
 * coroutines that move to other dispatchers won't see it. Use [withCanonicalLog]
 * for suspend code, or pair this with [withCanonicalCoroutineContext] inside the
 * block.
 *
 * **Adapter exceptions:** `WorkUnitAdapter.enrich` is expected not to throw —
 * it's library-author code, not adopter code, and a throwing adapter is a bug.
 * As a defensive guarantee: if it does throw an [Exception], the failure is
 * recorded in the canonical line itself via `canonical_log_enrich_error: true` and
 * `canonical_log_enrich_error_class: <fqcn>`, one WARN is logged to the
 * `io.github.alexhumphreys.canonicallog` logger, and the block's result is
 * returned (or its exception rethrown) as normal — telemetry failures never
 * replace the operation's result. The same policy covers a throwing [emit];
 * see [EmitFn].
 */
@OptIn(DelicateCanonicalLogApi::class)
public fun <T, R> withCanonicalLogBlocking(
    adapter: WorkUnitAdapter<T>,
    input: T,
    emit: EmitFn,
    block: (CanonicalLogContext) -> R,
): R {
    val ctx = CanonicalLogContext(adapter.describe(input))
    val previous = threadLocalContext.get()
    if (previous != null) {
        recordNesting(ctx, previous)
    }
    threadLocalContext.set(ctx)
    val startNs = System.nanoTime()
    // We catch Exception, not Throwable: Error subclasses (OOM, StackOverflow, etc.)
    // mean the JVM is in an unrecoverable state and trying to enrich/emit on top of
    // that is more likely to obscure the failure than to help. Restore the threadlocal
    // and let it propagate.
    val blockResult: Result<R> = try {
        Result.success(block(ctx))
    } catch (e: Exception) {
        Result.failure(e)
    } catch (t: Throwable) {
        threadLocalContext.set(previous)
        throw t
    }
    val outcome = outcomeOf(blockResult, startNs)
    // The finally guards the threadlocal against an Error escaping enrich (Exceptions
    // are swallowed inside runEnrich; Errors propagate per the rationale above).
    try {
        runEnrich(adapter, ctx, input, outcome)
    } finally {
        threadLocalContext.set(previous)
    }
    safeEmit(emit, ctx)
    return blockResult.getOrThrow()
}

/**
 * Suspend entry point for opening a canonical work unit.
 *
 * The [block] is invoked with a [CoroutineScope] receiver. This is load-bearing: it
 * means `async`, `launch`, and other [CoroutineScope] extensions called inside the
 * block resolve against a scope that carries the [CanonicalLogElement], not against
 * an outer scope (e.g. the test runner's `TestScope` or `runBlocking`'s scope).
 * Without the receiver, bare `async { ... }` inside the block silently inherits the
 * outer context, which has no canonical element, and contributions from inside the
 * async coroutine are lost.
 *
 * The receiver is an inner `coroutineScope`, so structured children the block
 * launches but never joins are awaited *before* the outcome is computed and the
 * canonical line is emitted: their contributions land in the snapshot, and a
 * failing child makes the work unit report [Outcome.Threw] rather than emitting
 * `Completed` while the caller sees the child's exception.
 *
 * Nesting follows the inner-shadows-outer contract described on
 * [withCanonicalLogBlocking]: while the inner unit is open, ambient contributions
 * route to the inner accumulator only; the outer resumes when it closes; each unit
 * emits its own line (inner first); the inner line carries `parent_work_unit_id`.
 *
 * **Cancellation:** when the block terminates with a [CancellationException] (request
 * timeout, client disconnect, `job.cancel()`), the outcome is [Outcome.Cancelled] and
 * the CE is rethrown after enrich/emit — never swallowed, so structured concurrency
 * stays intact. This works even though the coroutine is already cancelled at that
 * point: `enrich` and `emit` are deliberately non-suspending, so there is no
 * suspension point between catching the CE and rethrowing it for cancellation to
 * abort. Whatever the accumulator holds at that moment is what the line carries —
 * the same snapshot cutoff as every other path. One inherent race: if the caller's
 * job is cancelled *after* the block completes but before [withContext] returns,
 * [withContext] rethrows the job's CE even though the line already reported
 * `Completed` — the line reflects how the block itself terminated.
 *
 * **Threadlocal restoration:** unlike [withCanonicalLogBlocking], this variant does
 * not explicitly capture and restore a previous threadlocal binding around the block.
 * Restoration is delegated to the [CanonicalLogElement] `ThreadContextElement`
 * (`updateThreadContext`/`restoreThreadContext`), which saves the previous binding on
 * every dispatch and restores it on every suspension. This is also what implements
 * the nesting contract here: the same-`Key` element merge means the innermost unit's
 * element wins while it is open, and the enclosing binding (from an outer element or
 * an outer blocking entry point) is restored on each thread as the inner coroutine
 * leaves it. Pinned by `NestedWorkUnitTest`; no `CopyableThreadContextElement` is
 * needed for these semantics.
 *
 * Adapter exception handling matches [withCanonicalLogBlocking].
 */
@OptIn(DelicateCanonicalLogApi::class)
public suspend fun <T, R> withCanonicalLog(
    adapter: WorkUnitAdapter<T>,
    input: T,
    emit: EmitFn,
    block: suspend CoroutineScope.(CanonicalLogContext) -> R,
): R {
    val ctx = CanonicalLogContext(adapter.describe(input))
    // Parent detection reads the threadlocal, not the coroutine context: the threadlocal
    // always reflects the *innermost* active unit on this thread (a blocking inner unit
    // opened under a suspend outer is only visible there), and any element in the calling
    // coroutine's context has already been mirrored into the threadlocal at this point.
    threadLocalContext.get()?.let { parent ->
        recordNesting(ctx, parent)
    }
    val startNs = System.nanoTime()
    return withContext(CanonicalLogElement(ctx)) {
        // See [withCanonicalLogBlocking] for the rationale: Errors propagate; only
        // Exceptions become Outcome.Threw. The ThreadContextElement still cleans up
        // its threadlocal binding when the block escapes via Error.
        //
        // The inner coroutineScope is load-bearing: children the block launches on
        // its receiver scope but never joins must complete before we compute the
        // outcome and emit. Without it, enrich/emit run inside the withContext
        // lambda — *before* withContext joins its children — so an un-joined
        // launch races with emit (contributions lost) and a failing child would
        // surface to the caller after a canonical line already claimed Completed.
        val blockResult: Result<R> = try {
            Result.success(coroutineScope { block(ctx) })
        } catch (e: Exception) {
            Result.failure(e)
        }
        val outcome = outcomeOf(blockResult, startNs)
        runEnrich(adapter, ctx, input, outcome)
        safeEmit(emit, ctx)
        blockResult.getOrThrow()
    }
}

/**
 * Bridge an active blocking-thread canonical context into the coroutine context.
 *
 * Use from suspend code that's invoked from a blocking entry point (e.g. a Spring
 * servlet filter that called [withCanonicalLogBlocking]). After this wrapper, the
 * canonical bridge propagates correctly across `withContext`, `async`, and so on.
 *
 * This is *not* a way to open a new work unit — the lifecycle (describe / enrich /
 * emit) is the blocking entry point's responsibility. This helper only lifts the
 * already-open context into a coroutine-friendly form.
 *
 * If no work unit is active (no blocking entry point set the threadlocal), [block]
 * runs without a canonical context — contributions are silent no-ops, matching the
 * behaviour of [CanonicalLog.put] outside an active work unit.
 */
@OptIn(DelicateCanonicalLogApi::class)
public suspend fun <R> withCanonicalCoroutineContext(
    block: suspend CoroutineScope.() -> R,
): R {
    val element = threadLocalContext.get()?.let { CanonicalLogElement(it) }
    return if (element != null) {
        withContext(element, block)
    } else {
        coroutineScope(block)
    }
}

/**
 * Record the nesting markers on a unit opened inside [parent]: `parent_work_unit_id`
 * (immediate parent only) and `work_unit_depth` (parent's depth + 1). Top-level units
 * carry neither field — absent means depth 0. The depth is read back from the parent's
 * own field so it needs no extra state on [CanonicalLogContext]; a non-Long value there
 * (an adopter `put` on the reserved key) is treated as 0 rather than throwing.
 */
private fun recordNesting(ctx: CanonicalLogContext, parent: CanonicalLogContext) {
    ctx.put("parent_work_unit_id", parent.workUnit.id)
    val parentDepth = parent.fields["work_unit_depth"] as? Long ?: 0L
    ctx.put("work_unit_depth", parentDepth + 1)
}

/**
 * Map the block's [Result] to the lifecycle [Outcome]. Cancellation is classified by
 * exception type — see [Outcome.Cancelled] for why the current job is not consulted.
 * The caller rethrows via `getOrThrow()` either way; this only decides what the line says.
 */
private fun outcomeOf(blockResult: Result<*>, startNs: Long): Outcome = blockResult.fold(
    onSuccess = { Outcome.Completed(elapsedMs(startNs)) },
    onFailure = { cause ->
        when (cause) {
            is CancellationException -> Outcome.Cancelled(elapsedMs(startNs), cause)
            else -> Outcome.Threw(elapsedMs(startNs), cause)
        }
    },
)

private fun <T> runEnrich(
    adapter: WorkUnitAdapter<T>,
    ctx: CanonicalLogContext,
    input: T,
    outcome: Outcome,
) {
    try {
        adapter.enrich(ctx, input, outcome)
    } catch (enrichEx: Exception) {
        ctx.put("canonical_log_enrich_error", true)
        ctx.put("canonical_log_enrich_error_class", enrichEx::class.qualifiedName ?: "unknown")
        libraryLogger.warn(
            "adapter.enrich threw for work unit {}; failure recorded on the canonical line, block result unaffected",
            ctx.workUnit.id,
            enrichEx,
        )
    }
}

private fun safeEmit(emit: EmitFn, ctx: CanonicalLogContext) {
    try {
        emit(ctx)
    } catch (emitEx: Exception) {
        libraryLogger.warn(
            "emit threw for work unit {}; canonical line dropped, block result unaffected",
            ctx.workUnit.id,
            emitEx,
        )
    }
}

private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
