package io.github.alexhumphreys.canonicallog

import java.util.concurrent.Callable
import java.util.concurrent.Executor

/**
 * Helpers for carrying the active canonical log context across *plain thread* hops —
 * `ExecutorService.submit`, `CompletableFuture.supplyAsync(..., executor)`, Spring
 * `@Async`, and any other hand-off the coroutine bridge ([CanonicalLogElement])
 * doesn't cover.
 *
 * All three wrappers capture [currentCanonicalContext] **at wrap time** (i.e. on the
 * submitting thread) and bind/restore it around the task's execution on the worker
 * thread — including the MDC `work_unit_id` mirror (see [CanonicalLogMdc]), so log
 * lines the task writes correlate with the work unit's canonical line. If no work
 * unit is active at wrap time, the original task is returned unchanged — zero
 * overhead, matching the no-op behaviour of [CanonicalLog.put] outside a work unit.
 *
 * **The work unit does not wait for these tasks.** Propagation only makes the
 * accumulator *reachable* from the worker thread; the entry point still emits when
 * its own block completes. Contributions from a task that is still running at emit
 * time are silently cut off (same rule as detached coroutines — see the snapshot
 * cutoff in `BridgeContractTest`). Join the future/task before the work unit ends
 * if its contributions must land.
 *
 * **[Executor.propagatingCanonicalContext] captures per `execute` call**, on the
 * thread that calls `execute`. That makes it suitable for wrapping an executor that
 * receives work from many concurrent work units (each submission carries its own
 * context) — but only when submission happens on a thread where the work unit is
 * active. It is NOT suitable for executors whose submissions happen internally on
 * library-owned threads: e.g. wrapping OkHttp's `Dispatcher` executor does not fix
 * `Call.enqueue()`, because queued calls are promoted to the executor from OkHttp's
 * own threads, where no context is bound. See `OkHttpCanonicalInterceptor` docs.
 */
@OptIn(DelicateCanonicalLogApi::class)
public fun Runnable.propagatingCanonicalContext(): Runnable {
    val captured = currentCanonicalContext() ?: return this
    return Runnable {
        val previous = bindCurrentCanonicalContext(captured)
        val previousMdc = CanonicalLogMdc.install(captured)
        try {
            run()
        } finally {
            bindCurrentCanonicalContext(previous)
            CanonicalLogMdc.restore(previousMdc)
        }
    }
}

/** [Callable] variant of [Runnable.propagatingCanonicalContext]; same semantics. */
@OptIn(DelicateCanonicalLogApi::class)
public fun <V> Callable<V>.propagatingCanonicalContext(): Callable<V> {
    val captured = currentCanonicalContext() ?: return this
    return Callable {
        val previous = bindCurrentCanonicalContext(captured)
        val previousMdc = CanonicalLogMdc.install(captured)
        try {
            call()
        } finally {
            bindCurrentCanonicalContext(previous)
            CanonicalLogMdc.restore(previousMdc)
        }
    }
}

/**
 * Returns an [Executor] that propagates the canonical context active on the thread
 * calling `execute` into each submitted task. See the file-level caveats — notably
 * that capture happens per submission, on the submitting thread.
 */
public fun Executor.propagatingCanonicalContext(): Executor =
    Executor { command -> execute(command.propagatingCanonicalContext()) }
