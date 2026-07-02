package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.withCanonicalLog
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import kotlinx.coroutines.CoroutineScope
import java.time.Instant
import java.util.UUID

/**
 * The canonical line captured by [captureCanonicalLine] or [captureCanonicalLineBlocking],
 * plus the result (or exception) of the block that produced it.
 *
 * [fields] is the snapshot taken at emit time, exactly as a real sink would have seen it.
 * The capture adapter contributes nothing, so every field present is something the code
 * under test wrote — negative assertions ("no `error` on the success path") work directly
 * against [fields] without filtering out harness noise.
 */
public class CapturedCanonicalLine<R> internal constructor(
    public val fields: Map<String, Any>,
    public val workUnit: WorkUnit,
    public val outcome: Outcome,
    private val blockResult: Result<R>,
) {
    /**
     * The block's return value. Throws the block's exception if it failed — use
     * [exception] (or [outcome]) when asserting on failure lines.
     */
    public val result: R get() = blockResult.getOrThrow()

    /** The exception the block threw, or `null` if it completed normally. */
    public val exception: Throwable? get() = blockResult.exceptionOrNull()

    /** Shorthand for `fields[key]`. */
    public operator fun get(key: String): Any? = fields[key]
}

/**
 * Run [block] inside a canonical work unit and capture the line it would have emitted.
 *
 * Blocking counterpart of [captureCanonicalLine]; same semantics. Like the underlying
 * [withCanonicalLogBlocking], the canonical binding is set on the entering thread only —
 * a block that hops threads needs the propagation helpers, same as production code.
 */
public fun <R> captureCanonicalLineBlocking(
    block: (CanonicalLogContext) -> R,
): CapturedCanonicalLine<R> {
    val adapter = RecordingAdapter()
    var fields: Map<String, Any>? = null
    val blockResult: Result<R> = try {
        Result.success(withCanonicalLogBlocking(adapter, Unit, emit = { fields = it.snapshot() }, block))
    } catch (e: Exception) {
        Result.failure(e)
    }
    return CapturedCanonicalLine(checkNotNull(fields), adapter.workUnit, adapter.outcome, blockResult)
}

/**
 * Run [block] inside a canonical work unit and capture the line it would have emitted.
 *
 * This is the test-kit face of [withCanonicalLog]: the full lifecycle runs (describe,
 * block, enrich, emit), but the emitted snapshot is captured instead of written to a
 * sink, and the adapter contributes no fields of its own — what you assert on is purely
 * what the code under test wrote. The block keeps the production entry point's
 * [CoroutineScope] receiver, so `async`/`launch` children contribute correctly.
 *
 * Unlike the production entry points, a block exception is **not** rethrown: asserting
 * on failure lines is a primary use case. It is captured into the returned line
 * ([CapturedCanonicalLine.exception], [Outcome.Threw]). Only [Exception]s are captured;
 * an [Error] propagates, matching core — no line was emitted in that case anyway.
 */
public suspend fun <R> captureCanonicalLine(
    block: suspend CoroutineScope.(CanonicalLogContext) -> R,
): CapturedCanonicalLine<R> {
    val adapter = RecordingAdapter()
    var fields: Map<String, Any>? = null
    val blockResult: Result<R> = try {
        Result.success(withCanonicalLog(adapter, Unit, emit = { fields = it.snapshot() }, block))
    } catch (e: Exception) {
        Result.failure(e)
    }
    return CapturedCanonicalLine(checkNotNull(fields), adapter.workUnit, adapter.outcome, blockResult)
}

/**
 * Records the [WorkUnit] and [Outcome] for the captured line and deliberately
 * contributes no fields, so the snapshot is purely the code under test's writes.
 */
private class RecordingAdapter : WorkUnitAdapter<Unit> {
    lateinit var workUnit: WorkUnit
    lateinit var outcome: Outcome

    override fun describe(input: Unit): WorkUnit =
        WorkUnit(UUID.randomUUID().toString(), "test", Instant.now()).also { workUnit = it }

    override fun enrich(ctx: CanonicalLogContext, input: Unit, outcome: Outcome) {
        this.outcome = outcome
    }
}
