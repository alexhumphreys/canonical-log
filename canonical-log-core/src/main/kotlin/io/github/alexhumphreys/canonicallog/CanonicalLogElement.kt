package io.github.alexhumphreys.canonicallog

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal val threadLocalContext: ThreadLocal<CanonicalLogContext?> = ThreadLocal()

/**
 * Returns the active canonical log context for the current thread, or `null` if no
 * work unit is open on this thread. Adopters generally don't call this directly —
 * use [CanonicalLog.put] etc. instead. Useful for assertions in tests and for
 * filter implementations that need to reason about lifecycle state.
 */
public fun currentCanonicalContext(): CanonicalLogContext? = threadLocalContext.get()

/**
 * Bind the given context as the active canonical log context for the current thread,
 * returning the previous binding (or `null` if none was active).
 *
 * Most adopters should not call this directly — use [withCanonicalLogBlocking] or
 * [withCanonicalLog] instead. This is exposed for entry points that need to manage
 * the work-unit lifecycle outside a single function call (e.g. a servlet filter
 * that opens the work unit before chain dispatch and emits asynchronously after
 * the response completes).
 *
 * This binds the threadlocal only — it does not mirror the id into MDC, because its
 * restore-by-rebinding contract can't carry MDC's previous value through. Entry
 * points that want log correlation pair it with [CanonicalLogMdc.install]/
 * [CanonicalLogMdc.restore].
 */
@DelicateCanonicalLogApi
public fun bindCurrentCanonicalContext(context: CanonicalLogContext?): CanonicalLogContext? {
    val previous = threadLocalContext.get()
    threadLocalContext.set(context)
    return previous
}

public class CanonicalLogElement internal constructor(
    public val context: CanonicalLogContext,
) : AbstractCoroutineContextElement(Key), ThreadContextElement<CanonicalLogElement.Restore> {

    public companion object Key : CoroutineContext.Key<CanonicalLogElement>

    /**
     * Per-dispatch saved thread state: the previous threadlocal binding plus the
     * previous MDC `work_unit_id` value (see [CanonicalLogMdc]) — both restored
     * together on every suspension, so MDC stays in lockstep with the binding
     * across dispatcher switches.
     */
    public class Restore internal constructor(
        internal val context: CanonicalLogContext?,
        internal val mdcValue: String?,
    )

    @OptIn(DelicateCanonicalLogApi::class)
    override fun updateThreadContext(context: CoroutineContext): Restore {
        val previous = threadLocalContext.get()
        threadLocalContext.set(this.context)
        return Restore(previous, CanonicalLogMdc.install(this.context))
    }

    @OptIn(DelicateCanonicalLogApi::class)
    override fun restoreThreadContext(context: CoroutineContext, oldState: Restore) {
        threadLocalContext.set(oldState.context)
        CanonicalLogMdc.restore(oldState.mdcValue)
    }
}
