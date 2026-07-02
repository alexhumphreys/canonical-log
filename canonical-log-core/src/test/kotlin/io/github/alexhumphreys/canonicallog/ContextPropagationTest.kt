package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private val propagationAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

@OptIn(DelicateCanonicalLogApi::class)
class ContextPropagationTest : DescribeSpec({
    // Single worker thread on purpose: the restore assertion below probes the
    // threadlocal of the exact thread the wrapped task ran on.
    val pool = Executors.newFixedThreadPool(1)

    afterSpec { pool.shutdown() }

    describe("Runnable.propagatingCanonicalContext") {
        it("carries the context onto a pool thread and restores the worker's binding afterwards") {
            var snap: Map<String, Any> = emptyMap()
            withCanonicalLogBlocking(propagationAdapter, "wu", { snap = it.snapshot() }) {
                val task = Runnable { CanonicalLog.put("from_pool_thread", "yes") }
                    .propagatingCanonicalContext()
                pool.submit(task).get()

                // Restore check, while the work unit is still open: an un-wrapped
                // probe on the same (single) worker thread must see no context.
                val probe = pool.submit(Callable { currentCanonicalContext() }).get()
                probe shouldBe null
            }
            snap["from_pool_thread"] shouldBe "yes"
        }

        it("is the identity when no work unit is active") {
            val task = Runnable { }
            task.propagatingCanonicalContext() shouldBeSameInstanceAs task
        }
    }

    describe("Callable.propagatingCanonicalContext") {
        it("carries the context onto a pool thread and returns the callable's result") {
            var snap: Map<String, Any> = emptyMap()
            val result = withCanonicalLogBlocking(propagationAdapter, "wu", { snap = it.snapshot() }) {
                val task = Callable {
                    CanonicalLog.increment("pool_count", 3L)
                    "done"
                }.propagatingCanonicalContext()
                pool.submit(task).get()
            }
            result shouldBe "done"
            snap["pool_count"] shouldBe 3L
        }
    }

    describe("Executor.propagatingCanonicalContext") {
        it("captures per execute call, so two work units submitting to one executor don't bleed") {
            val propagating = pool.propagatingCanonicalContext()
            var snapA: Map<String, Any> = emptyMap()
            var snapB: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(propagationAdapter, "wu-a", { snapA = it.snapshot() }) {
                val fA = java.util.concurrent.CompletableFuture.runAsync(
                    { CanonicalLog.put("owner", "a") },
                    propagating,
                )
                fA.get()
            }
            withCanonicalLogBlocking(propagationAdapter, "wu-b", { snapB = it.snapshot() }) {
                val fB = java.util.concurrent.CompletableFuture.runAsync(
                    { CanonicalLog.put("owner", "b") },
                    propagating,
                )
                fB.get()
            }

            snapA["owner"] shouldBe "a"
            snapB["owner"] shouldBe "b"
        }
    }
})
