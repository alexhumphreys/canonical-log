package io.canonlog

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
 */
@DelicateCanonicalLogApi
public fun bindCurrentCanonicalContext(context: CanonicalLogContext?): CanonicalLogContext? {
    val previous = threadLocalContext.get()
    threadLocalContext.set(context)
    return previous
}

public class CanonicalLogElement internal constructor(
    public val context: CanonicalLogContext,
) : AbstractCoroutineContextElement(Key), ThreadContextElement<CanonicalLogContext?> {

    public companion object Key : CoroutineContext.Key<CanonicalLogElement>

    override fun updateThreadContext(context: CoroutineContext): CanonicalLogContext? {
        val previous = threadLocalContext.get()
        threadLocalContext.set(this.context)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: CanonicalLogContext?) {
        threadLocalContext.set(oldState)
    }
}
