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
 *
 * Relationship to [CanonicalLineWriter]: [EmitFn] is the raw lambda primitive the lifecycle
 * takes; [CanonicalLineWriter] is the named injectable seam integrations register/provide.
 * Entry points bridge them with `scope.emit(writer::write)`. Neither is deprecated.
 */
public typealias EmitFn = (CanonicalLogContext) -> Unit

/**
 * Blocking entry point for opening a canonical work unit.
 *
 * Lifecycle:
 *  1. Build a [CanonicalLogContext] from `adapter.describe(input)`.
 *  2. Install it as the current-thread canonical context, and mirror the work unit
 *     id into slf4j MDC under `work_unit_id` for log correlation (see
 *     [CanonicalLogMdc]; both are restored on unwind).
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
    val scope = openCanonicalWorkUnit(adapter, input)
    // We catch Exception, not Throwable: Error subclasses (OOM, StackOverflow, etc.)
    // mean the JVM is in an unrecoverable state and trying to enrich/emit on top of
    // that is more likely to obscure the failure than to help. Unbind and let it propagate.
    val blockResult: Result<R> = try {
        Result.success(block(scope.context))
    } catch (e: Exception) {
        Result.failure(e)
    } catch (t: Throwable) {
        scope.unbind()
        throw t
    }
    val outcome = scope.outcomeFor(blockResult.exceptionOrNull())
    // The finally guards the threadlocal against an Error escaping enrich (Exceptions
    // are swallowed inside enrich; Errors propagate per the rationale above).
    try {
        scope.enrich(adapter, input, outcome)
    } finally {
        scope.unbind()
    }
    scope.emit(emit)
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
 * every dispatch and restores it on every suspension. The element also mirrors the
 * work unit id into slf4j MDC under `work_unit_id` with the same per-dispatch
 * save/restore (see [CanonicalLogMdc]), so ordinary log lines correlate across
 * dispatcher switches without pairing this with `MDCContext`. This is also what implements
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
    // Seed synchronously on the caller's thread, BEFORE withContext: the ambient state we
    // want to capture (MDC entries, the current span) lives here, and would be gone once the
    // block switches dispatchers. Seed values are defaults the handler/enrich can overwrite.
    runSeed(adapter, ctx, input)
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
 * A blocking, thread-bound canonical work-unit lifecycle exposed as an open/close pair, for
 * entry points that receive their lifecycle boundaries as *separate callbacks* and so can't
 * use the [withCanonicalLogBlocking] closure form: a servlet filter that binds on request
 * entry but emits from an async listener, or a Micrometer `ObservationHandler` whose
 * `onScopeOpened` / `onStop` bracket the work.
 *
 * [openCanonicalWorkUnit] binds the context (threadlocal + MDC, recording nesting under any
 * already-active unit); the returned scope drives the tail — [outcomeFor] classifies a
 * terminal throwable into an [Outcome], [enrich] runs the adapter under the swallow-and-record
 * guard, [unbind] restores the previous binding, and [emit] publishes under the
 * swallow-and-warn guard. The caller sequences [unbind] against [enrich]/[emit] itself,
 * because async entry points must unbind on the originating thread *before* finalizing later
 * (and possibly elsewhere).
 *
 * This is the shared blocking lifecycle behind [withCanonicalLogBlocking] and the servlet
 * filter. It deliberately does not cover the suspend path ([withCanonicalLog]), whose binding
 * is the [CanonicalLogElement] `ThreadContextElement`'s responsibility — keeping the coroutine
 * bridge out of this primitive's scope.
 */
@DelicateCanonicalLogApi
public class CanonicalWorkUnitScope internal constructor(
    /** The bound work-unit context; contributors and [CanonicalLog] resolve to it while bound. */
    public val context: CanonicalLogContext,
    private val previousContext: CanonicalLogContext?,
    private val previousMdc: String?,
    private val startNs: Long,
) {
    /**
     * Classify how the work terminated, using elapsed time since [openCanonicalWorkUnit]:
     * `null` → [Outcome.Completed], a [CancellationException] → [Outcome.Cancelled], else
     * [Outcome.Threw].
     */
    public fun outcomeFor(error: Throwable?): Outcome = classifyOutcome(error, elapsedMs(startNs))

    /**
     * Run `adapter.enrich`, swallowing a throwing enrich and recording it on the line via
     * `canonical_log_enrich_error*` (see [WorkUnitAdapter]). Telemetry never fails the work.
     */
    public fun <T> enrich(adapter: WorkUnitAdapter<T>, input: T, outcome: Outcome) {
        runEnrich(adapter, context, input, outcome)
    }

    /** Restore the threadlocal + MDC binding displaced at [openCanonicalWorkUnit]. Call exactly once. */
    public fun unbind() {
        threadLocalContext.set(previousContext)
        CanonicalLogMdc.restore(previousMdc)
    }

    /** Publish the finalized line via [emit], swallowing and warning on a throwing emit (see [EmitFn]). */
    public fun emit(emit: EmitFn) {
        safeEmit(emit, context)
    }
}

/**
 * Open a blocking canonical work unit: describe it, record nesting if one is already active on
 * this thread, bind it as the current-thread context, and mirror the id into MDC. Returns a
 * [CanonicalWorkUnitScope] the caller finalizes — see that class for when to reach for this
 * over the [withCanonicalLogBlocking] closure.
 */
@DelicateCanonicalLogApi
public fun <T> openCanonicalWorkUnit(adapter: WorkUnitAdapter<T>, input: T): CanonicalWorkUnitScope {
    val ctx = CanonicalLogContext(adapter.describe(input))
    val previous = threadLocalContext.get()
    if (previous != null) {
        recordNesting(ctx, previous)
    }
    threadLocalContext.set(ctx)
    val previousMdc = CanonicalLogMdc.install(ctx)
    // Seed ambient state now, on the opening thread — after the threadlocal bind and MDC
    // mirror so a seed that logs (or reads the ambient context) sees `work_unit_id`, and
    // before the work runs so seed values are defaults the handler/enrich can overwrite.
    // This covers every blocking entry point (withCanonicalLogBlocking, the servlet filter,
    // the scheduling observation handler) with zero changes to them.
    runSeed(adapter, ctx, input)
    return CanonicalWorkUnitScope(ctx, previous, previousMdc, System.nanoTime())
}

/**
 * Record the nesting markers on a unit opened inside [parent]: `parent_work_unit_id`
 * (immediate parent only) and `work_unit_depth` (parent's depth + 1). Top-level units
 * carry neither field — absent means depth 0. The depth is read back from the parent's
 * own field so it needs no extra state on [CanonicalLogContext]; a non-Long value there
 * (an adopter `put` on the reserved key) is treated as 0 rather than throwing.
 */
private fun recordNesting(ctx: CanonicalLogContext, parent: CanonicalLogContext) {
    ctx.put(CanonicalFields.PARENT_WORK_UNIT_ID, parent.workUnit.id)
    val parentDepth = parent.fields[CanonicalFields.WORK_UNIT_DEPTH] as? Long ?: 0L
    ctx.put(CanonicalFields.WORK_UNIT_DEPTH, parentDepth + 1)
}

/**
 * Classify how a work unit terminated into a lifecycle [Outcome]: `null` (the block returned)
 * is [Outcome.Completed]; a [CancellationException] is [Outcome.Cancelled]; anything else is
 * [Outcome.Threw]. Cancellation is classified by exception type — see [Outcome.Cancelled] for
 * why the current job is not consulted. This only decides what the line says; the caller still
 * rethrows.
 */
private fun classifyOutcome(error: Throwable?, durationMs: Long): Outcome = when (error) {
    null -> Outcome.Completed(durationMs)
    is CancellationException -> Outcome.Cancelled(durationMs, error)
    else -> Outcome.Threw(durationMs, error)
}

/** Map the block's [Result] to an [Outcome]; the suspend path's [Result]-shaped entry to [classifyOutcome]. */
private fun outcomeOf(blockResult: Result<*>, startNs: Long): Outcome =
    classifyOutcome(blockResult.exceptionOrNull(), elapsedMs(startNs))

private fun <T> runSeed(
    adapter: WorkUnitAdapter<T>,
    ctx: CanonicalLogContext,
    input: T,
) {
    try {
        adapter.seed(ctx, input)
    } catch (seedEx: Exception) {
        ctx.put(CanonicalFields.SEED_ERROR, true)
        ctx.put(CanonicalFields.SEED_ERROR_CLASS, seedEx::class.qualifiedName ?: "unknown")
        libraryLogger.warn(
            "adapter.seed threw for work unit {}; failure recorded on the canonical line, block result unaffected",
            ctx.workUnit.id,
            seedEx,
        )
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
        ctx.put(CanonicalFields.ENRICH_ERROR, true)
        ctx.put(CanonicalFields.ENRICH_ERROR_CLASS, enrichEx::class.qualifiedName ?: "unknown")
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
