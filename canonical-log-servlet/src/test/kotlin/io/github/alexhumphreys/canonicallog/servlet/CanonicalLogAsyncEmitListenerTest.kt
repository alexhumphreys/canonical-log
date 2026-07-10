package io.github.alexhumphreys.canonicallog.servlet

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.openCanonicalWorkUnit
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    describe("concurrent finalizer hammer (todo 036)") {
        // Sequential orderings (above) prove the CAS makes single-flag state safe by
        // construction. This drives the *whole* finalize tail (outcomeFor -> enrich -> emit)
        // from truly concurrent threads racing on a CyclicBarrier, which is what a container
        // double-fire / ack-after-timeout race actually looks like.
        //
        // Decision (pinned here, see the listener's KDoc): enrich runs exactly once, for
        // exactly one Outcome value picked by whichever callback wins the CAS. Because
        // HttpWorkUnitAdapter-style adapters write fields for only ONE outcome branch inside a
        // single enrich() call, a torn line (fields from two different outcome branches) is
        // structurally impossible here -- the winning callback's outcome, and only that
        // outcome's fields, land. "One of the fired callbacks' outcomes" is therefore both the
        // legality bound and the only possible result.
        val executor: ExecutorService = Executors.newFixedThreadPool(3)

        it("emit==1, enrich==1, one sane outcome, no exception escapes, over 2k concurrent iterations") {
            try {
                repeat(2000) { iteration ->
                    val threadCount = 2 + (iteration % 2) // alternate 2 and 3 racing threads
                    val cause = RuntimeException("simulated container error #$iteration")
                    val fakeReq = FakeRequest(asyncSupported = true)
                    val asyncCtx = FakeAsyncContext(fakeReq.request, fakeReq.response)
                    val event = AsyncEvent(asyncCtx, fakeReq.request, fakeReq.response)
                    val errorEvent = AsyncEvent(asyncCtx, fakeReq.request, fakeReq.response, cause)

                    val adapter = RecordingHammerAdapter()
                    val scope = openCanonicalWorkUnit(adapter, Unit)
                    val emitCount = AtomicInteger(0)
                    var capturedLine: Map<String, Any>? = null

                    val listener = CanonicalLogAsyncEmitListener { error ->
                        val outcome = scope.outcomeFor(error)
                        scope.enrich(adapter, Unit, outcome)
                        scope.emit { ctx ->
                            emitCount.incrementAndGet()
                            capturedLine = ctx.snapshot()
                        }
                    }
                    scope.unbind()

                    val fired = (0 until threadCount).map {
                        AsyncCallback.entries[it % AsyncCallback.entries.size]
                    }
                    val barrier = CyclicBarrier(threadCount)
                    val escapedExceptions = mutableListOf<Throwable>()

                    val futures = fired.map { callback ->
                        executor.submit {
                            try {
                                barrier.await()
                                callback.fire(listener, event, errorEvent)
                            } catch (t: Throwable) {
                                synchronized(escapedExceptions) { escapedExceptions += t }
                            }
                        }
                    }
                    futures.forEach { it.get() }

                    withClue(iteration, fired) {
                        escapedExceptions shouldBe emptyList()
                        emitCount.get() shouldBe 1
                        adapter.enrichCount.get() shouldBe 1

                        val expectedKinds = fired.map { it.outcomeKind() }.toSet()
                        val line = capturedLine
                        checkNotNull(line) { "emit never captured a line" }
                        val actualKind = line["outcome_kind"] as String
                        expectedKinds shouldContain actualKind

                        when (actualKind) {
                            "threw" -> {
                                line.containsKey("error_class") shouldBe true
                                line.containsKey("cancel_reason") shouldBe false
                            }
                            "cancelled" -> {
                                line["cancel_reason"] shouldBe "async_timeout"
                                line.containsKey("error_class") shouldBe false
                            }
                            "completed" -> {
                                line.containsKey("error_class") shouldBe false
                                line.containsKey("cancel_reason") shouldBe false
                            }
                        }
                    }
                }
            } finally {
                executor.shutdown()
            }
        }
    }
})

private fun AsyncCallback.outcomeKind(): String = when (this) {
    AsyncCallback.COMPLETE_OK -> "completed"
    AsyncCallback.COMPLETE_WITH_ERROR, AsyncCallback.ERROR -> "threw"
    AsyncCallback.TIMEOUT -> "cancelled"
}

private fun withClue(iteration: Int, fired: List<AsyncCallback>, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        throw AssertionError("iteration=$iteration fired=$fired: ${t.message}", t)
    }
}

/** Records enrich invocations and writes a single outcome-branch's fields -- no torn state possible. */
private class RecordingHammerAdapter : WorkUnitAdapter<Unit> {
    val enrichCount = AtomicInteger(0)

    override fun describe(input: Unit): WorkUnit =
        WorkUnit(id = UUID.randomUUID().toString(), kind = "hammer", startedAt = Instant.now())

    override fun enrich(ctx: CanonicalLogContext, input: Unit, outcome: Outcome) {
        enrichCount.incrementAndGet()
        when (outcome) {
            is Outcome.Completed -> ctx.put("outcome_kind", "completed")
            is Outcome.Threw -> {
                ctx.put("outcome_kind", "threw")
                ctx.put("error_class", outcome.cause::class.qualifiedName ?: "unknown")
            }
            is Outcome.Cancelled -> {
                ctx.put("outcome_kind", "cancelled")
                ctx.put("cancel_reason", "async_timeout")
            }
        }
    }
}
