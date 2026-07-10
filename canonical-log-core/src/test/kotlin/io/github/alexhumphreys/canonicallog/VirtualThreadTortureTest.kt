package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeLessThan
import org.slf4j.MDC
import java.lang.management.ManagementFactory
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs only on JDK 21+ at *runtime* — [Executors.newVirtualThreadPerTaskExecutor] is a JDK 21
 * API. The library still targets Java 17 bytecode (main sources build with `-Xjdk-release=17`);
 * these are test-only sources compiled against the 25 toolchain, but must be skipped (not
 * failed) when re-run on a real JDK 17 launcher by CI's `test-jdk17` job. Every case below is
 * gated with `.config(enabled = jdk21Plus)` rather than an assumption, because Kotest's
 * DescribeSpec has no JUnit `Assumptions` equivalent for `it` blocks.
 */
private val jdk21Plus = Runtime.version().feature() >= 21

private const val HITS_PER_UNIT = 4L

private val tortureAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "vt-torture", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

private fun contribute(sharedPool: ExecutorService, viaSharedPool: Boolean, pin: Boolean) {
    if (pin) {
        // synchronized + a real sleep pins the current virtual thread to its carrier for the
        // duration of the block — the nastiest scheduling case for carrier reuse.
        val lock = Any()
        synchronized(lock) {
            Thread.sleep(1)
            CanonicalLog.increment("hits")
        }
        return
    }
    if (!viaSharedPool) {
        CanonicalLog.increment("hits")
        return
    }
    val latch = CountDownLatch(1)
    val task = Runnable {
        CanonicalLog.increment("hits")
        latch.countDown()
    }.propagatingCanonicalContext()
    sharedPool.execute(task)
    latch.await(10, TimeUnit.SECONDS)
}

private fun runUnit(
    token: String,
    idx: Int,
    sharedPool: ExecutorService,
    emitted: ConcurrentLinkedQueue<Map<String, Any>>,
) {
    withCanonicalLogBlocking(tortureAdapter, token, { emitted.add(it.snapshot()) }) { ctx ->
        ctx.put("token", token)
        ctx.put("field_$token", token)
        repeat(HITS_PER_UNIT.toInt()) { hitIdx ->
            contribute(
                sharedPool,
                viaSharedPool = hitIdx % 2 == 0,
                pin = idx % 3 == 0 && hitIdx == 0,
            )
        }
    }
}

private fun assertNoCarrierResidue(pool: ExecutorService, poolSize: Int) {
    val barrier = CyclicBarrier(poolSize)
    val futures = (0 until poolSize).map {
        pool.submit {
            barrier.await(10, TimeUnit.SECONDS)
            currentCanonicalContext() shouldBe null
            MDC.get(CanonicalLogMdc.KEY) shouldBe null
        }
    }
    futures.forEach { it.get(10, TimeUnit.SECONDS) }
}

class VirtualThreadTortureTest : DescribeSpec({

    afterEach { threadLocalContext.set(null) }

    describe("virtual-thread torture: ~100k mixed pinned/unpinned units, some hopping a shared platform pool") {
        it("emits exactly one line per unit, zero bleed, and leaves carrier pool clean").config(enabled = jdk21Plus) {
            val n = 100_000
            val sharedPool = Executors.newFixedThreadPool(16)
            val emitted = ConcurrentLinkedQueue<Map<String, Any>>()
            try {
                Executors.newVirtualThreadPerTaskExecutor().use { vtExecutor ->
                    val futures = (0 until n).map { i ->
                        val token = "vt-$i"
                        vtExecutor.submit { runUnit(token, i, sharedPool, emitted) }
                    }
                    futures.forEach { it.get(60, TimeUnit.SECONDS) }
                }

                val lines = emitted.toList()
                lines.size shouldBe n

                val tokensSeen = mutableSetOf<String>()
                lines.forEach { snap ->
                    val token = snap["token"] as String
                    tokensSeen.add(token) shouldBe true
                    val fieldKeys = snap.keys.filter { it.startsWith("field_") }
                    fieldKeys shouldBe listOf("field_$token")
                    snap["hits"] shouldBe HITS_PER_UNIT
                }
                tokensSeen.size shouldBe n

                assertNoCarrierResidue(sharedPool, 16)
            } finally {
                sharedPool.shutdown()
            }
        }
    }

    describe("threadlocal leak soak") {
        it("a finished virtual thread's accumulator becomes unreachable after gc").config(enabled = jdk21Plus) {
            class Payload
            val refQueue = ReferenceQueue<Payload>()
            var weakRef: WeakReference<Payload>? = null

            Executors.newVirtualThreadPerTaskExecutor().use { vtExecutor ->
                vtExecutor.submit {
                    withCanonicalLogBlocking(tortureAdapter, "leak-probe", { }) { ctx ->
                        val payload = Payload()
                        weakRef = WeakReference(payload, refQueue)
                        ctx.put("marker", payload.toString())
                    }
                }.get(10, TimeUnit.SECONDS)
            }

            var collected: java.lang.ref.Reference<out Payload>? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (collected == null && System.nanoTime() < deadline) {
                System.gc()
                collected = refQueue.remove(500)
            }
            collected shouldBe weakRef
        }

        it("500k churned virtual-thread units keep heap growth under a generous tripwire bound").config(enabled = jdk21Plus) {
            val heapBean = ManagementFactory.getMemoryMXBean()

            fun usedHeap(): Long {
                repeat(3) { System.gc() }
                Thread.sleep(100)
                return heapBean.heapMemoryUsage.used
            }

            // Warmup: prime JIT/executor pools so the measured run isn't paying one-time costs.
            Executors.newVirtualThreadPerTaskExecutor().use { vtExecutor ->
                (0 until 5_000).map { i ->
                    vtExecutor.submit {
                        withCanonicalLogBlocking(tortureAdapter, "warmup-$i", { }) { ctx ->
                            ctx.put("token", "warmup-$i")
                            CanonicalLog.increment("hits")
                        }
                    }
                }.forEach { it.get(30, TimeUnit.SECONDS) }
            }

            val before = usedHeap()

            Executors.newVirtualThreadPerTaskExecutor().use { vtExecutor ->
                (0 until 500_000).map { i ->
                    vtExecutor.submit {
                        withCanonicalLogBlocking(tortureAdapter, "churn-$i", { }) { ctx ->
                            ctx.put("token", "churn-$i")
                            CanonicalLog.increment("hits")
                        }
                    }
                }.forEach { it.get(60, TimeUnit.SECONDS) }
            }

            val after = usedHeap()
            val growthBytes = after - before
            val boundBytes = 50L * 1024 * 1024
            growthBytes shouldBeLessThan boundBytes
        }
    }
})
