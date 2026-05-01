package io.canonlog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.coroutineContext

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

/**
 * Bridge contract tests.
 *
 * Each test pins a specific propagation guarantee for the [CanonicalLogElement]
 * `ThreadContextElement` bridge. The accumulator's behaviour is contractual: the
 * snapshot taken when the work unit ends is the unit of observation, and these
 * tests describe what fields are guaranteed to be in it (and what aren't) as a
 * function of how coroutines are structured around contributions.
 */
class BridgeContractTest : DescribeSpec({

    describe("Bridge contract") {

        it("1. Same-dispatcher baseline: contributions on the entering thread land in the snapshot") {
            var snap: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    CanonicalLog.put("k", "v")
                }
            }
            snap["k"] shouldBe "v"
        }

        it("2. withContext(Dispatchers.IO) switch: contributions on the IO thread land in the snapshot") {
            var snap: Map<String, Any> = emptyMap()
            var enteringThread: String? = null
            var ioThread: String? = null
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    enteringThread = Thread.currentThread().name
                    withContext(Dispatchers.IO) {
                        ioThread = Thread.currentThread().name
                        CanonicalLog.put("from_io", "yes")
                    }
                }
            }
            snap["from_io"] shouldBe "yes"
            ioThread shouldNotBe enteringThread
        }

        it("3. async { ... }.await(): contributions inside the async block land in the snapshot") {
            var snap: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    val deferred = async(Dispatchers.IO) {
                        CanonicalLog.put("from_async", "yes")
                        42
                    }
                    deferred.await() shouldBe 42
                }
            }
            snap["from_async"] shouldBe "yes"
        }

        it("4. Parallel async fan-out: increments sum, no contributions lost") {
            val n = 100
            var snap: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    coroutineScope {
                        (1..n).map {
                            async(Dispatchers.IO) { CanonicalLog.increment("counter", 1L) }
                        }.awaitAll()
                    }
                }
            }
            snap["counter"] shouldBe n.toLong()
        }

        it("5. Nested withContext A→B→A: contributions in every layer land in the snapshot") {
            var snap: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    CanonicalLog.put("layer_a_outer", "yes")
                    withContext(Dispatchers.IO) {
                        CanonicalLog.put("layer_b", "yes")
                        withContext(Dispatchers.Default) {
                            CanonicalLog.put("layer_a_inner", "yes")
                        }
                        CanonicalLog.put("back_in_b", "yes")
                    }
                    CanonicalLog.put("back_in_a", "yes")
                }
            }
            snap["layer_a_outer"] shouldBe "yes"
            snap["layer_b"] shouldBe "yes"
            snap["layer_a_inner"] shouldBe "yes"
            snap["back_in_b"] shouldBe "yes"
            snap["back_in_a"] shouldBe "yes"
        }

        it("6. Orphaned launch outliving parent: post-emit contributions are NOT in the snapshot") {
            var snap: Map<String, Any> = emptyMap()
            val parentEmitted = CompletableDeferred<Unit>()
            val launchRanAndWrote = CompletableDeferred<Unit>()
            val external = CoroutineScope(Dispatchers.IO + SupervisorJob())

            try {
                runTest {
                    withCanonicalLog(
                        nullAdapter,
                        "wu",
                        emit = {
                            snap = it.snapshot()
                            parentEmitted.complete(Unit)
                        },
                    ) {
                        // Launch with the canonical context so the orphan can still find the
                        // accumulator after the parent's withContext has returned. The point
                        // is to verify that snapshot capture is a hard cutoff: late writes
                        // mutate the still-live context but do NOT retroactively appear in
                        // the emitted snapshot.
                        external.launch(coroutineContext) {
                            parentEmitted.await()
                            CanonicalLog.put("late", "yes")
                            launchRanAndWrote.complete(Unit)
                        }
                    }
                }

                // Side channel: the orphan really did execute (this assertion guards against
                // a vacuous pass where the launch never ran at all).
                launchRanAndWrote.await()
            } finally {
                external.cancel()
            }

            snap.containsKey("late") shouldBe false
        }

        it("7. Sharing: a child launch's contribution is visible to the parent before the parent's emit") {
            var snap: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    val children = (1..5).map {
                        launch(Dispatchers.IO) { CanonicalLog.put("child_$it", "yes") }
                    }
                    children.joinAll()
                    // Parent reads the live context (not the snapshot, which hasn't been
                    // taken yet — emit only fires after the whole block returns). The
                    // contributions from the children should already be observable here.
                    val live = threadLocalContext.get()!!.snapshot()
                    (1..5).forEach { i -> live["child_$i"] shouldBe "yes" }
                }
            }
            (1..5).forEach { i -> snap["child_$i"] shouldBe "yes" }
        }

        it("9. Blocking-entry → suspend bridge: withCanonicalCoroutineContext lifts the threadlocal binding into a coroutine context that survives dispatcher switches") {
            var snap: Map<String, Any> = emptyMap()

            // Simulate the Spring servlet filter pattern: the entry point is blocking
            // (sets only the threadlocal), the body is suspend (does dispatcher switches).
            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                runBlocking {
                    withCanonicalCoroutineContext {
                        withContext(Dispatchers.IO) {
                            CanonicalLog.put("from_io_in_suspend", "yes")
                        }
                        val d = async(Dispatchers.IO) {
                            CanonicalLog.increment("async_count", 1L)
                        }
                        d.await()
                    }
                }
            }

            snap["from_io_in_suspend"] shouldBe "yes"
            snap["async_count"] shouldBe 1L
        }

        it("10. withCanonicalCoroutineContext is a no-op outside an active work unit") {
            runBlocking {
                withCanonicalCoroutineContext {
                    // Should not throw; CanonicalLog.put silently no-ops because no context.
                    CanonicalLog.put("k", "v")
                }
            }
            // Implicit assertion: we got here without exception.
        }

        it("11. withCanonicalCoroutineContext rethrows from the block and restores the threadlocal") {
            var snap: Map<String, Any> = emptyMap()
            val ex = runCatching {
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    CanonicalLog.put("before_throw", "yes")
                    runBlocking {
                        withCanonicalCoroutineContext {
                            CanonicalLog.put("in_bridge", "yes")
                            withContext(Dispatchers.IO) {
                                CanonicalLog.put("on_io_before_throw", "yes")
                                throw IllegalStateException("kaboom")
                            }
                        }
                    }
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<IllegalStateException>()
            ex.message shouldBe "kaboom"
            // Contributions made before the throw must be in the snapshot.
            snap["before_throw"] shouldBe "yes"
            snap["in_bridge"] shouldBe "yes"
            snap["on_io_before_throw"] shouldBe "yes"
            // Threadlocal must be cleaned up after withCanonicalLogBlocking unwound.
            threadLocalContext.get() shouldBe null
        }

        it("8. Sharing: a child async's awaited contribution is in the parent's snapshot") {
            var snap: Map<String, Any> = emptyMap()
            runTest {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    val deferred = async(Dispatchers.IO) {
                        CanonicalLog.increment("child_count", 7L)
                        "child_returned"
                    }
                    deferred.await() shouldBe "child_returned"
                }
            }
            snap["child_count"] shouldBe 7L
        }
    }
})
