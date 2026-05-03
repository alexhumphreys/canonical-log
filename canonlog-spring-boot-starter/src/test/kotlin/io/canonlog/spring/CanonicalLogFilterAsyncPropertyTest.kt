package io.canonlog.spring

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property test pinning the filter's emit-exactly-once invariant under arbitrary
 * orderings of [AsyncListener] callbacks.
 *
 * Servlet containers vary in callback order: some fire `onError` before
 * `onComplete`, some fire `onTimeout` before `onComplete`, some fire `onComplete`
 * alone. Even pathological cases (multiple `onComplete`, no terminal callback at
 * all) shouldn't cause double-emit or hang. The [AtomicBoolean] guard inside
 * [CanonicalLogAsyncEmitListener] is what makes this safe; the wrapped emit lambda
 * passed in here counts invocations directly, so any regression that removes the
 * guard inside the listener will fail the property.
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

class CanonicalLogFilterAsyncPropertyTest : DescribeSpec({

    beforeSpec { PropertyTesting.defaultIterationCount = 200 }

    describe("CanonicalLogAsyncEmitListener + emit-once guard") {

        it("emits exactly once for any non-empty sequence of terminal callbacks") {
            val req = MockHttpServletRequest("GET", "/").apply { isAsyncSupported = true }
            val res = MockHttpServletResponse()
            val ctx = MockAsyncContext(req, res)
            val cause = RuntimeException("simulated container error")
            val event = AsyncEvent(ctx, req, res)
            val errorEvent = AsyncEvent(ctx, req, res, cause)

            checkAll(Arb.list(Arb.element<AsyncCallback>(*AsyncCallback.entries.toTypedArray()), 1..6)) { sequence ->
                val emitCount = AtomicInteger(0)
                val listener = CanonicalLogAsyncEmitListener { emitCount.incrementAndGet() }

                sequence.forEach { it.fire(listener, event, errorEvent) }

                emitCount.get() shouldBe 1
            }
        }

        it("captures the first callback's error: onError(cause) before onComplete(null) emits with the cause") {
            val req = MockHttpServletRequest("GET", "/").apply { isAsyncSupported = true }
            val res = MockHttpServletResponse()
            val ctx = MockAsyncContext(req, res)
            val cause = RuntimeException("simulated container error")
            val event = AsyncEvent(ctx, req, res)
            val errorEvent = AsyncEvent(ctx, req, res, cause)

            var captured: Throwable? = null
            val listener = CanonicalLogAsyncEmitListener { captured = it }

            listener.onError(errorEvent)
            listener.onComplete(event)

            captured shouldBe cause
        }

        it("captures TimeoutException when onTimeout fires first") {
            val req = MockHttpServletRequest("GET", "/").apply { isAsyncSupported = true }
            val res = MockHttpServletResponse()
            val ctx = MockAsyncContext(req, res)
            val event = AsyncEvent(ctx, req, res)

            var captured: Throwable? = null
            val listener = CanonicalLogAsyncEmitListener { captured = it }

            listener.onTimeout(event)
            listener.onComplete(event)

            (captured is TimeoutException) shouldBe true
        }

        it("onStartAsync re-registration: dispatch-and-redispatch terminal callbacks still single-emit") {
            val req = MockHttpServletRequest("GET", "/").apply { isAsyncSupported = true }
            val res = MockHttpServletResponse()
            val ctx = MockAsyncContext(req, res)
            val event = AsyncEvent(ctx, req, res)

            val emitCount = AtomicInteger(0)
            val listener = CanonicalLogAsyncEmitListener { emitCount.incrementAndGet() }

            // Simulate: handler dispatches again (onStartAsync), then dispatch runs and
            // completes (onComplete). The container may or may not deduplicate listener
            // registrations — the single-emit guard inside the listener makes that
            // irrelevant from our side. Fire onComplete twice to model the worst case
            // where two registrations of the same listener both fire.
            listener.onStartAsync(event)
            listener.onComplete(event)
            listener.onComplete(event)

            emitCount.get() shouldBe 1
        }
    }
})
