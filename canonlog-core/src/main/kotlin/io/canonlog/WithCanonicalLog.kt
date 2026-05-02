package io.canonlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The hand-off the entry point uses to publish the finalized canonical line.
 *
 * **`emit` must not throw.** By the time it runs, the work unit is already finalized
 * — the block returned (or threw), the adapter's `enrich` ran, and the threadlocal
 * has been restored. A throwing emit is a wiring bug at the entry-point level (the
 * canonical sink itself failed). The library does not attempt to recover: the
 * exception propagates out of `withCanonicalLog{,Blocking}` and replaces the block's
 * result. If the block had already thrown, the emit exception will replace *that*
 * (unhelpfully) — so keep emit implementations dead simple.
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
 * Calling this inside an already-active work unit is **undefined**. Nested work
 * units are not yet supported (see CLAUDE.md). Calling it inside a coroutine
 * that does dispatcher switches is **undefined** for the inner switches —
 * the threadlocal is set on the entering thread only; coroutines that move to
 * other dispatchers won't see it. Use [withCanonicalLog] for suspend code, or
 * pair this with [withCanonicalCoroutineContext] inside the block.
 *
 * **Adapter exceptions:** `WorkUnitAdapter.enrich` is expected not to throw —
 * it's library-author code, not adopter code, and a throwing adapter is a bug.
 * As a defensive guarantee: if it does throw, the failure is recorded in the
 * canonical line itself via `canonlog_enrich_error: true` and
 * `canonlog_enrich_error_class: <fqcn>`. If the block succeeded, the enrich
 * exception propagates to the caller (something is genuinely broken). If the
 * block also threw, the block's exception wins; the enrich failure is captured
 * only via the marker fields on the canonical line.
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
    val outcome = blockResult.fold(
        onSuccess = { Outcome.Completed(elapsedMs(startNs)) },
        onFailure = { Outcome.Threw(elapsedMs(startNs), it) },
    )
    val enrichExceptionToPropagate = runEnrich(adapter, ctx, input, outcome, blockResult.isSuccess)
    threadLocalContext.set(previous)
    emit(ctx)
    if (enrichExceptionToPropagate != null) throw enrichExceptionToPropagate
    return blockResult.getOrThrow()
}

/**
 * Suspend entry point for opening a canonical work unit.
 *
 * The [block] is invoked with a [CoroutineScope] receiver. This is load-bearing: it
 * means `async`, `launch`, and other [CoroutineScope] extensions called inside the
 * block resolve against the scope created by `withContext(CanonicalLogElement)`,
 * not against an outer scope (e.g. the test runner's `TestScope` or `runBlocking`'s
 * scope). Without the receiver, bare `async { ... }` inside the block silently
 * inherits the outer context, which has no canonical element, and contributions from
 * inside the async coroutine are lost.
 *
 * Calling this inside an already-active work unit is **undefined**. Nested work
 * units are not yet supported (see CLAUDE.md).
 *
 * **Threadlocal restoration:** unlike [withCanonicalLogBlocking], this variant does
 * not explicitly capture and restore a previous threadlocal binding around the block.
 * Restoration is delegated to the [CanonicalLogElement] `ThreadContextElement`
 * (`updateThreadContext`/`restoreThreadContext`), which is correct for the supported
 * case (a top-level entry from suspend code, where there is no prior threadlocal
 * binding on the dispatching thread). For the unsupported nested case, behaviour
 * diverges from the blocking variant — but nesting is undefined regardless.
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
    val startNs = System.nanoTime()
    return withContext(CanonicalLogElement(ctx)) {
        // See [withCanonicalLogBlocking] for the rationale: Errors propagate; only
        // Exceptions become Outcome.Threw. The ThreadContextElement still cleans up
        // its threadlocal binding when the block escapes via Error.
        val blockResult: Result<R> = try {
            Result.success(block(ctx))
        } catch (e: Exception) {
            Result.failure(e)
        }
        val outcome = blockResult.fold(
            onSuccess = { Outcome.Completed(elapsedMs(startNs)) },
            onFailure = { Outcome.Threw(elapsedMs(startNs), it) },
        )
        val enrichExceptionToPropagate = runEnrich(adapter, ctx, input, outcome, blockResult.isSuccess)
        emit(ctx)
        if (enrichExceptionToPropagate != null) throw enrichExceptionToPropagate
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

private fun <T> runEnrich(
    adapter: WorkUnitAdapter<T>,
    ctx: CanonicalLogContext,
    input: T,
    outcome: Outcome,
    blockSucceeded: Boolean,
): Throwable? = try {
    adapter.enrich(ctx, input, outcome)
    null
} catch (enrichEx: Throwable) {
    ctx.put("canonlog_enrich_error", true)
    ctx.put("canonlog_enrich_error_class", enrichEx::class.qualifiedName ?: "unknown")
    // If the block succeeded, the enrich exception is the only failure signal — propagate
    // it. If the block failed too, its exception is more useful to the caller; the enrich
    // failure is captured only via the marker fields on the canonical line.
    if (blockSucceeded) enrichEx else null
}

private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
