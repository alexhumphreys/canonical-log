package io.canonlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

public typealias EmitFn = (CanonicalLogContext) -> Unit

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
    try {
        val result = block(ctx)
        adapter.enrich(ctx, input, Outcome.Completed(elapsedMs(startNs)))
        return result
    } catch (t: Throwable) {
        adapter.enrich(ctx, input, Outcome.Threw(elapsedMs(startNs), t))
        throw t
    } finally {
        threadLocalContext.set(previous)
        emit(ctx)
    }
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
        try {
            val result = block(ctx)
            adapter.enrich(ctx, input, Outcome.Completed(elapsedMs(startNs)))
            result
        } catch (t: Throwable) {
            adapter.enrich(ctx, input, Outcome.Threw(elapsedMs(startNs), t))
            throw t
        } finally {
            emit(ctx)
        }
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

private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
