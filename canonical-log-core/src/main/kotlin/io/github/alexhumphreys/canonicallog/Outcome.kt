package io.github.alexhumphreys.canonicallog

import kotlin.coroutines.cancellation.CancellationException

public sealed class Outcome {
    public abstract val durationMs: Long

    /**
     * The block returned normally. Whether the work was successful in a business sense
     * is up to the adapter and handler to decide via [CanonicalLogContext.markFailed]
     * or status-code inspection.
     */
    public data class Completed(override val durationMs: Long) : Outcome()

    /**
     * The block threw. Always indicates failure at the lifecycle level.
     *
     * A [CancellationException] is never mapped here — it produces [Cancelled].
     */
    public data class Threw(override val durationMs: Long, val cause: Throwable) : Outcome()

    /**
     * The block was cancelled: it terminated with a [CancellationException] (client
     * disconnect, request timeout, structured-concurrency cancellation).
     *
     * Cancellation is not a failure. Adapters should mark the line `cancelled=true`
     * plus a `cancel_reason` and must **not** set `error=true` — a cancelled request
     * polluting error rates is exactly what this outcome exists to prevent.
     *
     * Classification is by exception type (`is CancellationException`), not by
     * inspecting the current job: a CE that leaks from an unrelated child (e.g.
     * `await()` on an externally cancelled `Deferred`) is still reported as
     * Cancelled. The entry points always rethrow the exception after enrich/emit —
     * cancellation is observed, never swallowed, so structured concurrency stays
     * intact.
     */
    public data class Cancelled(
        override val durationMs: Long,
        val cause: CancellationException,
    ) : Outcome()
}
