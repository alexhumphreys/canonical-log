package io.github.alexhumphreys.canonicallog.servlet

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property test pinning the emit-exactly-once invariant of [CanonicalLogAsyncEmitListener]
 * under arbitrary orderings of [AsyncListener] callbacks. This is the async half of the HTTP
 * lifecycle in [runCanonicalHttpRequest], and the hardest to get right.
 *
 * Servlet containers vary in callback order: some fire `onError` before `onComplete`, some
 * fire `onTimeout` before `onComplete`, some fire `onComplete` alone. Even pathological cases
 * (multiple `onComplete`, no terminal callback at all) shouldn't cause double-emit or hang.
 * The `AtomicBoolean` guard inside the listener is what makes this safe; the wrapped emit
 * lambda counts invocations directly, so any regression that removes the guard fails here.
 *
 * (Relocated from the Spring starter in todo 021 along with the listener itself — the Spring
 * mocks it used were replaced by this module's hand-rolled fakes.)
 */
private enum class AsyncCallback {
    COMPLETE_OK, COMPLETE_WITH_ERROR, ERROR, TIMEOUT;

    fun fire(listener: AsyncListener, event: AsyncEvent, errorEvent: AsyncEvent) {
        when (this) {
            COMPLETE_OK -> listener.onComplete(event)
            COMPLETE_WITH_ERROR -> listener.onComplete(errorEvent)
            ERROR -> listener.onError(errorEvent)
            TIMEOUT -> listener.onTimeout(event)
        }
    }
}

class CanonicalLogAsyncEmitListenerTest : DescribeSpec({

    beforeSpec { PropertyTesting.defaultIterationCount = 200 }

    val fake = FakeRequest(asyncSupported = true)
    val ctx = FakeAsyncContext(fake.request, fake.response)

    describe("CanonicalLogAsyncEmitListener + emit-once guard") {

        it("emits exactly once for any non-empty sequence of terminal callbacks") {
            val cause = RuntimeException("simulated container error")
            val event = AsyncEvent(ctx, fake.request, fake.response)
            val errorEvent = AsyncEvent(ctx, fake.request, fake.response, cause)

            checkAll(Arb.list(Arb.element<AsyncCallback>(*AsyncCallback.entries.toTypedArray()), 1..6)) { sequence ->
                val emitCount = AtomicInteger(0)
                val listener = CanonicalLogAsyncEmitListener { emitCount.incrementAndGet() }

                sequence.forEach { it.fire(listener, event, errorEvent) }

                emitCount.get() shouldBe 1
            }
        }

        it("captures the first callback's error: onError(cause) before onComplete(null) emits with the cause") {
            val cause = RuntimeException("simulated container error")
            val event = AsyncEvent(ctx, fake.request, fake.response)
            val errorEvent = AsyncEvent(ctx, fake.request, fake.response, cause)

            var captured: Throwable? = null
            val listener = CanonicalLogAsyncEmitListener { captured = it }

            listener.onError(errorEvent)
            listener.onComplete(event)

            captured shouldBe cause
        }

        it("synthesizes a cancellation (not an error) when onTimeout fires first") {
            val event = AsyncEvent(ctx, fake.request, fake.response)

            var captured: Throwable? = null
            val listener = CanonicalLogAsyncEmitListener { captured = it }

            listener.onTimeout(event)
            listener.onComplete(event)

            // A CancellationException is what makes the lifecycle's emit produce
            // Outcome.Cancelled (cancelled=true) instead of Threw (error=true).
            (captured is CancellationException) shouldBe true
            (captured is AsyncTimeoutCancellationException) shouldBe true
        }

        it("onStartAsync re-registration: dispatch-and-redispatch terminal callbacks still single-emit") {
            val event = AsyncEvent(ctx, fake.request, fake.response)

            val emitCount = AtomicInteger(0)
            val listener = CanonicalLogAsyncEmitListener { emitCount.incrementAndGet() }

            // Simulate: handler dispatches again (onStartAsync), then dispatch runs and
            // completes (onComplete). The container may or may not deduplicate listener
            // registrations — the single-emit guard inside the listener makes that
            // irrelevant. Fire onComplete twice to model the worst case where two
            // registrations of the same listener both fire.
            listener.onStartAsync(event)
            listener.onComplete(event)
            listener.onComplete(event)

            emitCount.get() shouldBe 1
        }
    }
})
