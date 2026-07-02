package io.github.alexhumphreys.canonicallog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
 * Calling this inside an already-active work unit is **undefined**. Nested work
 * units are not yet supported (see CLAUDE.md). Calling it inside a coroutine
 * that does dispatcher switches is **undefined** for the inner switches —
 * the threadlocal is set on the entering thread only; coroutines that move to
 * other dispatchers won't see it. Use [withCanonicalLog] for suspend code, or
 * pair this with [withCanonicalCoroutineContext] inside the block.
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
        val outcome = blockResult.fold(
            onSuccess = { Outcome.Completed(elapsedMs(startNs)) },
            onFailure = { Outcome.Threw(elapsedMs(startNs), it) },
        )
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
