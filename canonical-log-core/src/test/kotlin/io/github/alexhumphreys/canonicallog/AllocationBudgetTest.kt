package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import java.lang.management.ManagementFactory
import java.time.Instant

/**
 * Allocation budgets for the hot path (todo 044).
 *
 * The library's central claim is that instrumenting a work unit costs approximately
 * nothing per contribution. This suite turns that into a regression gate: it measures
 * bytes allocated by the measuring thread across a tight loop of one operation, divides
 * by the iteration count, and asserts the result stays under a documented budget.
 *
 * **Why `getThreadAllocatedBytes` and not JMH's `-prof gc`.** Both measure the same
 * thing, but the JMH profiler reports a *rate* derived from GC bookkeeping and needs
 * forked JVMs and multi-second runs to settle — too slow and too noisy for a per-PR
 * check. `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` is exact per-thread
 * TLAB accounting, needs no agent and no fork, and the whole suite runs in about a
 * second. The `benchmarks` module keeps the JMH harness for throughput numbers and as
 * an independent cross-check of these figures; this test is the gate.
 *
 * **Why the numbers are stable enough to assert on.** Allocation is a property of the
 * bytecode that runs, not of timing: the same sequence of `new`s happens interpreted or
 * compiled. The one direction JIT moves the number is *down* (escape analysis can
 * scalar-replace a box), and every assertion here is an upper bound, so that can only
 * make the test pass. What does perturb a single run is a stray class load or deopt on
 * the measuring thread, which shows up as a fraction of a byte spread over 200k
 * iterations — hence [allocatedBytesPerOp] takes the **minimum of three runs**, and the
 * zero-allocation budgets are stated as "< 1 byte/op" rather than exactly 0.
 *
 * **Budgets carry ~2x headroom** over the measured figure. They are a ratchet against
 * an accidental order-of-magnitude regression (a captured lambda added to a hot path, a
 * defensive copy in `put`), not a pin on the exact byte count — a change that moves a
 * number *within* budget needs no test edit, and a change that blows a budget deserves
 * the conversation the failure forces. Measured on JDK 25 (temurin); the JDK 17 launcher
 * job (`-Ptest.jdk=17`) runs the same budgets.
 *
 * The two headline results this suite pins:
 *  - **`put` on an open unit allocates nothing at all.** Not "a little" — zero bytes,
 *    ambient (`CanonicalLog.put`) or direct (`ctx.put`). Same for every no-op call made
 *    when no work unit is open, which is the path every `CanonicalLog` call in an
 *    un-instrumented test or a startup code path takes.
 *  - **`increment` allocates 24 bytes/op** — the boxed `Long` result and nothing more,
 *    which is the floor for a `Map<String, Any>` accumulator.
 */
private val budgetAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "budget", Instant.EPOCH)
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("duration_ms", outcome.durationMs)
    }
}

/** Threshold for "this path allocates nothing", allowing one stray event per run. */
private const val ZERO = 1.0

private val threadMx: com.sun.management.ThreadMXBean? =
    (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }

/**
 * Bytes allocated per invocation of [op] by the calling thread, as the minimum of three
 * measured runs (see the suite KDoc for why the minimum). [warmup] iterations run first
 * so class loading and the initial JIT compile land outside every measured window.
 */
private fun allocatedBytesPerOp(warmup: Int, iterations: Int, op: () -> Unit): Double {
    val mx = requireNotNull(threadMx)
    // Thread.threadId() is JDK 19+; the library test suites also run on a JDK 17 launcher
    // (-Ptest.jdk=17), where only the deprecated Thread.id exists.
    @Suppress("DEPRECATION")
    val threadId = Thread.currentThread().id
    repeat(warmup) { op() }
    var best = Double.MAX_VALUE
    repeat(3) {
        val before = mx.getThreadAllocatedBytes(threadId)
        for (i in 0 until iterations) op()
        val after = mx.getThreadAllocatedBytes(threadId)
        val perOp = (after - before).toDouble() / iterations
        if (perOp < best) best = perOp
    }
    return best
}

/**
 * Assert [op] stays within [budgetBytesPerOp]. Reports the measured figure in the failure
 * message either way — a regression should say how far it went over, not just that it did.
 */
private fun budget(name: String, budgetBytesPerOp: Double, warmup: Int, iterations: Int, op: () -> Unit) {
    val measured = allocatedBytesPerOp(warmup, iterations, op)
    check(measured <= budgetBytesPerOp) {
        "$name allocated $measured bytes/op, over its budget of $budgetBytesPerOp bytes/op. " +
            "If the increase is intended and justified, raise the budget in AllocationBudgetTest " +
            "and say why in the comment next to it."
    }
    println("ALLOC $name: $measured bytes/op (budget $budgetBytesPerOp)")
}

@OptIn(DelicateCanonicalLogApi::class)
class AllocationBudgetTest : DescribeSpec({

    beforeSpec {
        // HotSpot supports and enables per-thread allocation accounting by default. If a
        // JVM ever doesn't, skipping beats failing: the budgets are a regression gate, not
        // a correctness property, and no other suite depends on this one.
        if (threadMx == null) {
            println("Skipping AllocationBudgetTest: per-thread allocation accounting unavailable")
        }
    }

    describe("contributions to an open work unit") {
        it("put allocates nothing").config(enabled = threadMx != null) {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.EPOCH))
            val previous = bindCurrentCanonicalContext(ctx)
            try {
                // Overwriting an existing key: no map node, no box, no defensive copy.
                budget("ctx.put", ZERO, 20_000, 200_000) { ctx.put("key", "value") }
                budget("CanonicalLog.put", ZERO, 20_000, 200_000) { CanonicalLog.put("key", "value") }
            } finally {
                bindCurrentCanonicalContext(previous)
            }
        }

        it("increment stays within its budget").config(enabled = threadMx != null) {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.EPOCH))
            val previous = bindCurrentCanonicalContext(ctx)
            try {
                // 24 bytes/op — the boxed Long result, and nothing else. That is the floor
                // while the accumulator is a ConcurrentHashMap<String, Any>: `merge` has to
                // hand back a reference. Getting here took making the remapping function a
                // stateless singleton (`SumLongs`) and reading the type conflict off merge's
                // return value; the previous capturing lambda cost 80 bytes/op, the extra 56
                // being a fresh lambda instance plus a Ref.ObjectRef per call. Keep the budget
                // tight — its whole job is to catch a capture creeping back in here.
                budget("ctx.increment", 48.0, 20_000, 200_000) { ctx.increment("count") }
                budget("CanonicalLog.increment", 48.0, 20_000, 200_000) { CanonicalLog.increment("count") }
            } finally {
                bindCurrentCanonicalContext(previous)
            }
        }
    }

    describe("contributions with no work unit open") {
        // The un-instrumented path: app startup, unit tests that open no unit, library code
        // running outside a request. A threadlocal read and a null check, and it must cost
        // literally nothing — otherwise every adopter pays for instrumentation they aren't using.
        it("no-op calls allocate nothing").config(enabled = threadMx != null) {
            currentCanonicalContext().let { check(it == null) { "expected no bound work unit" } }
            budget("CanonicalLog.put (no unit)", ZERO, 20_000, 200_000) { CanonicalLog.put("key", "value") }
            budget("CanonicalLog.increment (no unit)", ZERO, 20_000, 200_000) { CanonicalLog.increment("count") }
        }
    }

    describe("per-work-unit costs") {
        it("a blocking round trip stays within its budget").config(enabled = threadMx != null) {
            // Open, bind, MDC-mirror, ten puts, enrich, unbind, emit. ~586 bytes measured for
            // the whole lifecycle — the floor for one instrumented operation, amortized over
            // whatever real work the unit wraps.
            budget("withCanonicalLogBlocking (10 fields)", 1_300.0, 5_000, 50_000) {
                withCanonicalLogBlocking(budgetAdapter, "wu", { }) { ctx ->
                    for (n in 0 until 10) ctx.put(FIELD_KEYS[n], n.toLong())
                }
            }
        }

        it("snapshot and JSON rendering stay within budget").config(enabled = threadMx != null) {
            val ctx = CanonicalLogContext(WorkUnit("id", "kind", Instant.EPOCH))
            for (n in 0 until 20) ctx.put(FIELD_KEYS[n], if (n % 3 == 0) "value_$n" else n.toLong())

            // ~912 bytes: a fresh HashMap plus 20 nodes (C2 scalar-replaces part of it). Emit-time only, once per work unit.
            budget("snapshot (20 fields)", 4_000.0, 5_000, 50_000) { ctx.snapshot() }

            val snapshot = ctx.snapshot()
            // ~1272 bytes: StringBuilder growth, the sorted key list, and the result String.
            budget("canonicalLineJson (20 fields)", 2_600.0, 5_000, 50_000) { canonicalLineJson(snapshot) }
        }
    }
})

/** Pre-built keys so the measured loops never allocate a key string themselves. */
private val FIELD_KEYS: Array<String> = Array(32) { "field_$it" }
