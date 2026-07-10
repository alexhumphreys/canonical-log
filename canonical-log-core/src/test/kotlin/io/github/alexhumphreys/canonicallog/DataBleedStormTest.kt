package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private const val HITS_PER_UNIT = 4L

private val stormAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "storm", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

/**
 * One `increment("hits")` contribution. On odd sub-indices it's a blocking hop through the
 * shared, `propagatingCanonicalContext()`-wrapped pool — captured on the calling thread,
 * joined via a latch before returning (the documented "join before block return" contract).
 */
private fun contributeBlocking(sharedPool: ExecutorService, viaSharedPool: Boolean) {
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

/** Suspend counterpart: the latch wait is offloaded to [Dispatchers.IO] so it never starves a limited dispatcher. */
private suspend fun contributeSuspend(sharedPool: ExecutorService, viaSharedPool: Boolean) {
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
    withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }
}

private fun runUnitBlocking(
    token: String,
    sharedPool: ExecutorService,
    emitted: ConcurrentLinkedQueue<Map<String, Any>>,
) {
    withCanonicalLogBlocking(stormAdapter, token, { emitted.add(it.snapshot()) }) { ctx ->
        ctx.put("token", token)
        ctx.put("field_$token", token)
        repeat(HITS_PER_UNIT.toInt()) { idx -> contributeBlocking(sharedPool, viaSharedPool = idx % 2 == 0) }
    }
}

private suspend fun runUnitSuspend(
    token: String,
    sharedPool: ExecutorService,
    emitted: ConcurrentLinkedQueue<Map<String, Any>>,
) {
    withCanonicalLog(stormAdapter, token, { emitted.add(it.snapshot()) }) { ctx ->
        ctx.put("token", token)
        ctx.put("field_$token", token)
        repeat(HITS_PER_UNIT.toInt()) { idx -> contributeSuspend(sharedPool, viaSharedPool = idx % 2 == 0) }
    }
}

/**
 * Run [n] concurrent mixed work units, half via [withCanonicalLogBlocking] on [blockingPool]
 * (a platform-thread pool), half via suspend [withCanonicalLog] alternating [Dispatchers.IO] /
 * [Dispatchers.Default] — all contending on the same [sharedPool] for their "hits" hops.
 * Every unit's emitted snapshot lands in [emitted]. Blocking units are joined before this
 * function returns (via a coroutine-scoped `future.get()`), matching the documented
 * join-before-block-return contract for propagated hops.
 */
private suspend fun runStorm(
    prefix: String,
    n: Int,
    blockingPool: ExecutorService,
    sharedPool: ExecutorService,
    emitted: ConcurrentLinkedQueue<Map<String, Any>>,
) {
    coroutineScope {
        val blockingFutures = mutableListOf<Future<*>>()
        for (i in 0 until n) {
            val token = "$prefix-$i"
            if (i % 2 == 0) {
                blockingFutures += blockingPool.submit { runUnitBlocking(token, sharedPool, emitted) }
            } else {
                val dispatcher = if (i % 4 == 1) Dispatchers.IO else Dispatchers.Default
                launch(dispatcher) { runUnitSuspend(token, sharedPool, emitted) }
            }
        }
        withContext(Dispatchers.IO) {
            blockingFutures.forEach { it.get(30, TimeUnit.SECONDS) }
        }
    }
}

/**
 * Submit exactly [poolSize] tasks that rendezvous on a [CyclicBarrier], forcing every thread of
 * [pool] to run one probe concurrently — the only way to guarantee coverage of every thread
 * rather than getting lucky repeats on a subset. Each probe asserts no dead context / MDC
 * residue survives from prior work.
 */
private fun assertNoThreadResidue(pool: ExecutorService, poolSize: Int) {
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

/**
 * Asserts the library's headline guarantee — "no data bleed between concurrent work units" —
 * as an explicit negative property, in-process. Complements the property tests (which only
 * assert *my contributions arrive*, so bleed only surfaces if it corrupts a sum) and the
 * out-of-process `ab` load test (asserts uniform fields end-to-end but isn't part of
 * `./gradlew test`). Two failure modes covered:
 *
 *  - **Cross-unit bleed**: a shared executor, a missed restore in the propagation bridge, or a
 *    captured-wrong-context bug lets unit A's contribution land in unit B's line.
 *  - **Pool residue**: a finished unit's context left bound to a pooled thread — invisible to
 *    per-unit assertions because the *next* task silently contributes to a dead accumulator.
 */
class DataBleedStormTest : DescribeSpec({

    afterEach { threadLocalContext.set(null) }

    describe("storm: ~500 concurrent mixed units over shared propagating infrastructure") {
        it("emits exactly one line per unit, each with its own token/field/hits, and leaves no thread residue") {
            val blockingPoolSize = 32
            val sharedPoolSize = 16
            val blockingPool = Executors.newFixedThreadPool(blockingPoolSize)
            val sharedPool = Executors.newFixedThreadPool(sharedPoolSize)
            val n = 500
            try {
                repeat(3) { iteration ->
                    val emitted = ConcurrentLinkedQueue<Map<String, Any>>()

                    runStorm("storm$iteration", n, blockingPool, sharedPool, emitted)

                    val lines = emitted.toList()
                    lines.size shouldBe n

                    val tokensSeen = mutableSetOf<String>()
                    lines.forEach { snap ->
                        val token = snap["token"] as String
                        // Uniqueness: a duplicated or bled-in token would fail this add.
                        tokensSeen.add(token) shouldBe true

                        // Only the unit's own field_* key may be present — a foreign field_*
                        // key (any value) is bleed, full stop.
                        val fieldKeys = snap.keys.filter { it.startsWith("field_") }
                        fieldKeys shouldBe listOf("field_$token")

                        // Exact sum: bleed-in inflates, bleed-out deflates.
                        snap["hits"] shouldBe HITS_PER_UNIT
                    }
                    tokensSeen.size shouldBe n
                }

                // Residue probe: after every storm iteration, every thread of both shared
                // pools must show a clean slate (no dead context, no stale MDC entry).
                assertNoThreadResidue(blockingPool, blockingPoolSize)
                assertNoThreadResidue(sharedPool, sharedPoolSize)
            } finally {
                blockingPool.shutdown()
                sharedPool.shutdown()
            }
        }
    }

    // Behaviour-pinning tests for the documented misuse modes of CanonicalWorkUnitScope
    // (see its KDoc invariant 1 in WithCanonicalLog.kt). These pin what the KDoc warns
    // about — they don't bless it as correct usage.
    describe("CanonicalWorkUnitScope misuse pins (KDoc invariant 1: unbind exactly once, on the opening thread)") {
        it("a skipped unbind leaves dead context residue on the opening thread, and the next unit opened there records a phantom parent_work_unit_id") {
            val singleThreadPool = Executors.newSingleThreadExecutor()
            try {
                singleThreadPool.submit {
                    val leaked = openCanonicalWorkUnit(stormAdapter, "leaked")
                    // Misuse: no unbind() call here. The opening thread's binding is left
                    // pointing at the finished, dead context -- exactly the documented residue.
                    currentCanonicalContext() shouldBe leaked.context

                    // The next unit opened on this thread sees the dead context as its
                    // "parent" and records a phantom nesting marker against it.
                    val next = openCanonicalWorkUnit(stormAdapter, "next")
                    next.context.snapshot()[CanonicalFields.PARENT_WORK_UNIT_ID] shouldBe leaked.context.workUnit.id
                    next.context.snapshot()[CanonicalFields.WORK_UNIT_DEPTH] shouldBe 1L

                    // Clean up manually so the pool thread doesn't carry residue into other tests.
                    next.unbind()
                    leaked.unbind()
                }.get(10, TimeUnit.SECONDS)
            } finally {
                singleThreadPool.shutdown()
            }
        }

        it("unbind() called from a different thread than the opener leaves the opening thread's binding intact") {
            val openerPool = Executors.newSingleThreadExecutor()
            val otherPool = Executors.newSingleThreadExecutor()
            try {
                val scope = openerPool.submit(Callable { openCanonicalWorkUnit(stormAdapter, "wu") })
                    .get(10, TimeUnit.SECONDS)

                // Misuse: unbind from a thread that never opened the scope.
                otherPool.submit { scope.unbind() }.get(10, TimeUnit.SECONDS)

                // The opening thread's threadlocal is untouched by that call -- residue persists.
                val stillBoundOnOpener = openerPool.submit(Callable { currentCanonicalContext() })
                    .get(10, TimeUnit.SECONDS)
                stillBoundOnOpener shouldBe scope.context

                // Clean up for real, on the opening thread this time.
                openerPool.submit { scope.unbind() }.get(10, TimeUnit.SECONDS)
            } finally {
                openerPool.shutdown()
                otherPool.shutdown()
            }
        }
    }
})
