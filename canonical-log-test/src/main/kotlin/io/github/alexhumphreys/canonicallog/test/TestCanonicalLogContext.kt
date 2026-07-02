package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.bindCurrentCanonicalContext
import java.time.Instant

/**
 * Build a standalone [CanonicalLogContext] for unit-testing a contributor directly —
 * call a listener/interceptor with it, then assert on `snapshot()` — without running
 * a full work-unit lifecycle. Wraps the `@DelicateCanonicalLogApi` constructor so the
 * test doesn't need the opt-in itself.
 */
@OptIn(DelicateCanonicalLogApi::class)
public fun testCanonicalLogContext(
    id: String = "test-work-unit",
    kind: String = "test",
): CanonicalLogContext = CanonicalLogContext(WorkUnit(id, kind, Instant.now()))

/**
 * Run [block] with [context] bound as the current thread's canonical context, so
 * ambient calls ([CanonicalLog.put], contributors reading the threadlocal) land in it.
 * The previous binding is restored afterwards, even if [block] throws.
 *
 * This is binding only — no describe/enrich/emit runs. Pair with
 * [testCanonicalLogContext] to unit-test code that contributes ambiently; use
 * [captureCanonicalLineBlocking] when you want the full lifecycle and the emitted line.
 */
@OptIn(DelicateCanonicalLogApi::class)
public fun <R> withBoundCanonicalContext(context: CanonicalLogContext, block: () -> R): R {
    val previous = bindCurrentCanonicalContext(context)
    try {
        return block()
    } finally {
        bindCurrentCanonicalContext(previous)
    }
}
