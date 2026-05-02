package io.canonlog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("outcome", outcome::class.simpleName)
    }
}

private fun nestedHelper(value: String) {
    CanonicalLog.put("nested_field", value)
}

@OptIn(DelicateCanonicalLogApi::class)
class WithCanonicalLogTest : DescribeSpec({

    describe("withCanonicalLogBlocking") {
        it("calls emit exactly once on success and returns block result") {
            val emitCount = AtomicInteger()
            var captured: CanonicalLogContext? = null

            val result = withCanonicalLogBlocking(
                adapter = nullAdapter,
                input = "wu-1",
                emit = { ctx -> emitCount.incrementAndGet(); captured = ctx },
            ) { ctx ->
                ctx.put("inside", "yes")
                "ok"
            }

            result shouldBe "ok"
            emitCount.get() shouldBe 1
            captured?.snapshot()?.get("inside") shouldBe "yes"
            captured?.snapshot()?.get("outcome") shouldBe "Completed"
        }

        it("calls emit exactly once on exception and rethrows") {
            val emitCount = AtomicInteger()
            var captured: CanonicalLogContext? = null

            val ex = runCatching {
                withCanonicalLogBlocking(
                    adapter = nullAdapter,
                    input = "wu-1",
                    emit = { ctx -> emitCount.incrementAndGet(); captured = ctx },
                ) {
                    error("boom")
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<IllegalStateException>()
            ex.message shouldBe "boom"
            emitCount.get() shouldBe 1
            captured?.snapshot()?.get("outcome") shouldBe "Threw"
        }

        it("saves and restores prior thread-local") {
            val outerCtx = CanonicalLogContext(WorkUnit("outer", "test", Instant.now()))
            threadLocalContext.set(outerCtx)
            try {
                CanonicalLog.put("from_outer", "yes")

                withCanonicalLogBlocking(
                    adapter = nullAdapter,
                    input = "inner",
                    emit = { },
                ) {
                    CanonicalLog.put("from_inner", "yes")
                    threadLocalContext.get() shouldNotBe null
                    threadLocalContext.get()?.workUnit?.id shouldBe "inner"
                }

                threadLocalContext.get() shouldBe outerCtx
                outerCtx.snapshot()["from_outer"] shouldBe "yes"
                outerCtx.snapshot().containsKey("from_inner") shouldBe false
            } finally {
                threadLocalContext.set(null)
            }
        }

        it("CanonicalLog.put from nested function lands in the right context") {
            var snap: Map<String, Any> = emptyMap()
            withCanonicalLogBlocking(
                adapter = nullAdapter,
                input = "wu",
                emit = { ctx -> snap = ctx.snapshot() },
            ) {
                nestedHelper("layer1")
            }
            snap["nested_field"] shouldBe "layer1"
        }
    }

    describe("withCanonicalLog (suspend)") {
        it("propagates context across withContext(Dispatchers.IO)") {
            var snap: Map<String, Any> = emptyMap()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    withContext(Dispatchers.IO) {
                        CanonicalLog.put("from_io", "yes")
                    }
                }
            }
            snap["from_io"] shouldBe "yes"
        }

        it("propagates context to child coroutines and increments accumulate") {
            var snap: Map<String, Any> = emptyMap()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    coroutineScope {
                        val tasks = (1..100).map {
                            async(Dispatchers.IO) { CanonicalLog.increment("parallel_count", 1L) }
                        }
                        tasks.awaitAll()
                    }
                }
            }
            snap["parallel_count"] shouldBe 100L
        }

        it("calls emit exactly once on success") {
            val emitCount = AtomicInteger()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { emitCount.incrementAndGet() }) { "ok" }
            }
            emitCount.get() shouldBe 1
        }

        it("calls emit exactly once on exception and rethrows") {
            val emitCount = AtomicInteger()
            val ex = runCatching {
                runBlocking {
                    withCanonicalLog(nullAdapter, "wu", { emitCount.incrementAndGet() }) {
                        error("boom")
                    }
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<IllegalStateException>()
            ex.message shouldBe "boom"
            emitCount.get() shouldBe 1
        }
    }
})
