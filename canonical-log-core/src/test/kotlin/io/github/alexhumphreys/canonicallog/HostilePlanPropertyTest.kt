package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hostile companion to [AccumulatorPropertyTest] (todo 037). The happy-path property pins
 * "every contribution lands"; this spec composes the *documented contracts under stress* —
 * failing children ([HAction.Throws]), inner-shadows-outer nesting ([HAction.NestedUnit]),
 * the suspend→blocking→suspend seam ([HAction.BlockingBridge]), joined plain-thread hops
 * ([HAction.ExecutorHop]), and the snapshot cutoff for detached work
 * ([HAction.DetachedLaunch]) — into random structures, where interaction bugs live.
 *
 * The oracle is a three-way classification computed by walking the plan (see [classify]):
 *
 *  - **must contain**: keys whose every increment is on a deterministically-completing
 *    path — asserted to sum *exactly*;
 *  - **must NOT contain**: keys written only post-emit (detached bodies, namespaced
 *    `det_*` so membership is unambiguous), only on never-executed paths (sequential
 *    tails after a deterministic throw), or only on another unit's line — asserted absent;
 *  - **may contain**: keys with at least one increment racing a cancellation (a sibling
 *    fan-out branch that throws) — asserted only for type sanity (and a floor when the
 *    key also has deterministic increments).
 *
 * Per-line key ownership is exact: every key on a line must be in that unit's
 * must/may sets (plus the nesting markers), so cross-unit bleed of any kind fails.
 * Additionally, for plans that deterministically throw, the exception must reach the
 * caller and the outer line must still have been emitted with [Outcome.Threw]; nested
 * units' `parent_work_unit_id`/`work_unit_depth` must reconstruct the generated nesting
 * exactly, and definitely-executed nested units must emit exactly one line with the
 * outcome their own subtree dictates.
 *
 * Real dispatchers and a real executor, not `runTest` — the properties are about real
 * parallelism (the existing file's precedent). Latches are released in `finally`, so no
 * schedule can deadlock an iteration.
 */
class HostilePlanPropertyTest : DescribeSpec({

    // Cached pool: detached tasks park on the latch while joined hop tasks need threads,
    // so a bounded pool could deadlock an iteration.
    val executor: ExecutorService = Executors.newCachedThreadPool()
    beforeSpec { installCoroutineLeakProbe() }
    afterSpec {
        executor.shutdownNow()
        uninstallCoroutineLeakProbe()
    }

    describe("Hostile plan invariants") {

        it("must/mustNot/may oracle holds for arbitrary hostile coroutine plans") {
            val baselineJobs = captureBaselineJobs()

            checkAll(200, arbHostilePlan(maxDepth = 3)) { plan ->
                val lines = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, Any>>>())
                val outcomes = ConcurrentHashMap<String, Outcome>()
                val adapter = object : WorkUnitAdapter<String> {
                    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
                    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                        outcomes[input] = outcome
                    }
                }
                val emit: EmitFn = { ctx -> lines += ctx.workUnit.id to ctx.snapshot() }
                val env = HostileEnv(
                    adapter = adapter,
                    emit = emit,
                    executor = executor,
                    latch = CountDownLatch(1),
                    futures = CopyOnWriteArrayList(),
                )

                var thrown: Exception? = null
                try {
                    runBlocking {
                        withCanonicalLog(adapter, OUTER_ID, emit) { runHostile(plan, this, env) }
                    }
                } catch (e: Exception) {
                    thrown = e
                } finally {
                    // Release detached bodies only after the outer emit — this is what makes
                    // "detached contributions are absent from every snapshot" deterministic.
                    env.latch.countDown()
                }
                // Join detached work so nothing leaks into the next iteration.
                env.futures.forEach { it.get(30, TimeUnit.SECONDS) }
                assertNoLeakedCoroutines(baselineJobs)

                val planThrows = computeThrows(plan)
                if (planThrows) {
                    // The exception reaches the caller, but only after the line was emitted
                    // (the outer unit's exactly-one-line assertion below proves the emit).
                    thrown.shouldNotBeNull()
                    (thrown is CancellationException) shouldBe false
                    (outcomes[OUTER_ID] is Outcome.Threw) shouldBe true
                } else {
                    thrown shouldBe null
                    (outcomes[OUTER_ID] is Outcome.Completed) shouldBe true
                }

                classify(plan).forEach { (id, exp) ->
                    val unitLines = lines.filter { it.first == id }
                    when (exp.mode) {
                        Mode.DEFINITE -> unitLines.size shouldBe 1
                        Mode.RACY -> (unitLines.size <= 1) shouldBe true
                        Mode.NEVER -> unitLines.size shouldBe 0
                    }
                    if (exp.mode == Mode.DEFINITE && id != OUTER_ID) {
                        val outcome = outcomes[id]
                        if (exp.throwsInside) {
                            (outcome is Outcome.Threw) shouldBe true
                        } else {
                            (outcome is Outcome.Completed) shouldBe true
                        }
                    }

                    val snap = unitLines.firstOrNull()?.second ?: return@forEach

                    // mustContain: exact sums for keys with no racy increments.
                    exp.sums.filterKeys { it !in exp.may }.forEach { (k, v) -> snap[k] shouldBe v }
                    // mayContain: type sanity only; a deterministic floor when the key also
                    // has definite increments (those are guaranteed to land).
                    exp.may.forEach { k ->
                        val v = snap[k]
                        if (k in exp.sums) {
                            v.shouldNotBeNull()
                            (v as Long) shouldBeGreaterThanOrEqual exp.sums.getValue(k)
                        } else if (v != null) {
                            (v is Long) shouldBe true
                        }
                    }
                    // mustNotContain: keys written only post-emit / on dead paths / on other lines.
                    (exp.never - exp.sums.keys - exp.may).forEach { k ->
                        snap.containsKey(k) shouldBe false
                    }
                    // No key from anywhere else may appear on this line at all.
                    val allowed = exp.sums.keys + exp.may +
                        setOf(CanonicalFields.PARENT_WORK_UNIT_ID, CanonicalFields.WORK_UNIT_DEPTH)
                    (snap.keys - allowed) shouldBe emptySet<String>()

                    // The nesting chain reconstructs the generated structure exactly.
                    if (exp.parentId != null) {
                        snap[CanonicalFields.PARENT_WORK_UNIT_ID] shouldBe exp.parentId
                        snap[CanonicalFields.WORK_UNIT_DEPTH] shouldBe exp.depth
                    } else {
                        snap.containsKey(CanonicalFields.PARENT_WORK_UNIT_ID) shouldBe false
                        snap.containsKey(CanonicalFields.WORK_UNIT_DEPTH) shouldBe false
                    }
                }
            }
        }
    }
})

private const val OUTER_ID = "outer"

/** Stackless marker thrown by [HAction.Throws]; cheap enough for 200 property iterations. */
private class HostileMarker : RuntimeException("hostile plan marker", null, false, false)

/**
 * The hostile plan ADT: the happy-path variants from [AccumulatorPropertyTest]'s `Action`
 * plus the five hostile nodes from todo 037. [NestedUnit.id] is assigned post-generation
 * by [assignIds] so each nested unit's line can be matched back to its node.
 */
private sealed class HAction {
    data class Increment(val key: String, val by: Long) : HAction()
    data class Sequential(val children: List<HAction>) : HAction()
    data class WithSwitch(val to: HDispatcher, val child: HAction) : HAction()
    data class InScope(val child: HAction) : HAction()
    data class FanOut(val branches: List<HAction>) : HAction()
    data class AsyncAwait(val on: HDispatcher, val child: HAction) : HAction()
    data class BareAsync(val on: HDispatcher, val child: HAction) : HAction()

    /** Runs [child], then throws [HostileMarker]. In a fan-out branch this cancels siblings. */
    data class Throws(val child: HAction) : HAction()

    /** Opens an inner `withCanonicalLog` around [child]: inner contributions shadow the outer line. */
    data class NestedUnit(val child: HAction, val id: String = "") : HAction()

    /** Suspend → blocking → suspend seam: `withContext(IO)` into a blocking fn that re-enters via `withCanonicalCoroutineContext`. */
    data class BlockingBridge(val child: HAction) : HAction()

    /** Runs [child] on a shared executor via `propagatingCanonicalContext`, joined — contributions must land. */
    data class ExecutorHop(val child: HAction) : HAction()

    /**
     * Runs [child] on the executor, NOT joined, gated on a latch released only after the
     * outer emit: contributions (namespaced `det_*`) must be absent from every snapshot —
     * the documented cutoff — and nothing throws.
     */
    data class DetachedLaunch(val child: HAction) : HAction()
}

private enum class HDispatcher { IO, DEFAULT, UNCONFINED }

private fun HDispatcher.asCoroutineDispatcher(): CoroutineDispatcher = when (this) {
    HDispatcher.IO -> Dispatchers.IO
    HDispatcher.DEFAULT -> Dispatchers.Default
    HDispatcher.UNCONFINED -> Dispatchers.Unconfined
}

private fun arbHostilePlan(maxDepth: Int): Arb<HAction> =
    arbHAction(maxDepth, insideDetached = false).map { assignIds(it, AtomicInteger()) }

private fun arbHAction(maxDepth: Int, insideDetached: Boolean): Arb<HAction> {
    val keys = Arb.element("a", "b", "c", "d")
    val amounts = Arb.long(1L..100L)
    val dispatchers = Arb.element(HDispatcher.IO, HDispatcher.DEFAULT, HDispatcher.UNCONFINED)

    val leaf: Arb<HAction> = Arb.bind(keys, amounts) { k, n -> HAction.Increment(k, n) }
    if (maxDepth <= 0) return leaf

    val child = arbHAction(maxDepth - 1, insideDetached)

    val variants = mutableListOf(
        Arb.list(child, 0..3).map { HAction.Sequential(it) },
        Arb.bind(dispatchers, child) { d, c -> HAction.WithSwitch(d, c) },
        child.map { HAction.InScope(it) },
        Arb.list(child, 1..3).map { HAction.FanOut(it) },
        Arb.bind(dispatchers, child) { d, c -> HAction.AsyncAwait(d, c) },
        Arb.bind(dispatchers, child) { d, c -> HAction.BareAsync(d, c) },
        child.map { HAction.Throws(it) },
        child.map { HAction.BlockingBridge(it) },
        child.map { HAction.ExecutorHop(it) },
    )
    if (!insideDetached) {
        // Nested units / further detachments inside a detached body run after the outer
        // emit, which would mean lines emitted outside the iteration's observation window —
        // keep the oracle decidable by excluding them there (increments, throws, hops, and
        // fan-out remain, which is what the cutoff contract is about).
        variants += child.map { HAction.NestedUnit(it) }
        variants += arbHAction(maxDepth - 1, insideDetached = true).map { HAction.DetachedLaunch(it) }
    }
    return Arb.choice(leaf, *variants.toTypedArray())
}

/** Rebuild the plan, giving every [HAction.NestedUnit] a unique id for line matching. */
private fun assignIds(a: HAction, counter: AtomicInteger): HAction = when (a) {
    is HAction.Increment -> a
    is HAction.Sequential -> HAction.Sequential(a.children.map { assignIds(it, counter) })
    is HAction.WithSwitch -> a.copy(child = assignIds(a.child, counter))
    is HAction.InScope -> a.copy(child = assignIds(a.child, counter))
    is HAction.FanOut -> HAction.FanOut(a.branches.map { assignIds(it, counter) })
    is HAction.AsyncAwait -> a.copy(child = assignIds(a.child, counter))
    is HAction.BareAsync -> a.copy(child = assignIds(a.child, counter))
    is HAction.Throws -> a.copy(child = assignIds(a.child, counter))
    is HAction.NestedUnit -> HAction.NestedUnit(assignIds(a.child, counter), "nested_${counter.incrementAndGet()}")
    is HAction.BlockingBridge -> a.copy(child = assignIds(a.child, counter))
    is HAction.ExecutorHop -> a.copy(child = assignIds(a.child, counter))
    is HAction.DetachedLaunch -> a.copy(child = assignIds(a.child, counter))
}

/** Per-iteration wiring passed down the plan. [inDetached] namespaces keys as `det_*`. */
private data class HostileEnv(
    val adapter: WorkUnitAdapter<String>,
    val emit: EmitFn,
    val executor: ExecutorService,
    val latch: CountDownLatch,
    val futures: MutableList<Future<*>>,
    val inDetached: Boolean = false,
)

private suspend fun runHostile(action: HAction, scope: CoroutineScope, env: HostileEnv) {
    when (action) {
        is HAction.Increment ->
            CanonicalLog.increment(if (env.inDetached) "det_${action.key}" else action.key, action.by)

        is HAction.Sequential -> action.children.forEach { runHostile(it, scope, env) }

        is HAction.WithSwitch -> withContext(action.to.asCoroutineDispatcher()) {
            runHostile(action.child, this, env)
        }

        is HAction.InScope -> coroutineScope { runHostile(action.child, this, env) }

        is HAction.FanOut -> coroutineScope {
            action.branches.map { branch ->
                async(Dispatchers.IO) { runHostile(branch, this, env) }
            }.awaitAll()
        }

        is HAction.AsyncAwait -> coroutineScope {
            async(action.on.asCoroutineDispatcher()) { runHostile(action.child, this, env) }.await()
        }

        is HAction.BareAsync ->
            scope.async(action.on.asCoroutineDispatcher()) { runHostile(action.child, this, env) }.await()

        is HAction.Throws -> {
            runHostile(action.child, scope, env)
            throw HostileMarker()
        }

        is HAction.NestedUnit -> withCanonicalLog(env.adapter, action.id, env.emit) {
            runHostile(action.child, this, env)
        }

        is HAction.BlockingBridge -> withContext(Dispatchers.IO) {
            // The CanonicalLogElement has bound the threadlocal on this IO thread, so the
            // blocking helper sees the ambient context and can re-enter suspend land.
            blockingHop(action.child, env)
        }

        is HAction.ExecutorHop -> {
            // Capture on the current (bound) thread; join before returning, so the hop's
            // contributions are inside the snapshot cutoff.
            val task = Callable { blockingHop(action.child, env) }.propagatingCanonicalContext()
            withContext(Dispatchers.IO) { env.executor.submit(task).get(30, TimeUnit.SECONDS) }
        }

        is HAction.DetachedLaunch -> {
            val task = Runnable {
                try {
                    env.latch.await()
                    blockingHop(action.child, env.copy(inDetached = true))
                } catch (_: Exception) {
                    // A throwing detached body must never surface anywhere; swallowed by design.
                }
            }.propagatingCanonicalContext()
            env.futures += env.executor.submit(task)
        }
    }
}

/**
 * A blocking function that relies on the ambient threadlocal binding and re-enters
 * suspend land via [withCanonicalCoroutineContext] — the suspend→blocking→suspend seam.
 */
private fun blockingHop(child: HAction, env: HostileEnv) {
    runBlocking {
        withCanonicalCoroutineContext { runHostile(child, this, env) }
    }
}

// ---------------------------------------------------------------------------
// The oracle
// ---------------------------------------------------------------------------

private enum class Mode {
    /** Deterministically executes, and its contributions land before the unit's emit. */
    DEFINITE,

    /** Races a cancellation (a throwing fan-out sibling): may or may not execute/land. */
    RACY,

    /** Deterministically does not execute before the relevant emit (dead tail or detached). */
    NEVER,
}

private class UnitExpectation(
    val parentId: String?,
    val depth: Long,
    /** Whether this unit's own open/emit deterministically happens ([Mode.DEFINITE]), races, or never happens. */
    val mode: Mode,
    /** Whether this unit's subtree deterministically throws out of the unit (given the unit runs). */
    val throwsInside: Boolean,
) {
    /** Deterministic per-key sums; exact for keys not also in [may]. */
    val sums = mutableMapOf<String, Long>()

    /** Keys with at least one increment racing cancellation. */
    val may = mutableSetOf<String>()

    /** Keys with increments only on never-landing paths. */
    val never = mutableSetOf<String>()
}

/**
 * Whether the subtree deterministically propagates an exception to its caller *if executed*.
 * A [HAction.Throws] anywhere reachable does, unless swallowed by [HAction.DetachedLaunch]
 * (the only swallowing structure — nested units rethrow). With multiple racing throwers the
 * exception identity is nondeterministic, but *that something non-CE propagates* is not:
 * `coroutineScope` rethrows the first failure, and sibling CEs are suppressed.
 */
private fun computeThrows(a: HAction): Boolean = when (a) {
    is HAction.Increment -> false
    is HAction.Throws -> true
    is HAction.DetachedLaunch -> false
    is HAction.Sequential -> a.children.any(::computeThrows)
    is HAction.FanOut -> a.branches.any(::computeThrows)
    is HAction.WithSwitch -> computeThrows(a.child)
    is HAction.InScope -> computeThrows(a.child)
    is HAction.AsyncAwait -> computeThrows(a.child)
    is HAction.BareAsync -> computeThrows(a.child)
    is HAction.NestedUnit -> computeThrows(a.child)
    is HAction.BlockingBridge -> computeThrows(a.child)
    is HAction.ExecutorHop -> computeThrows(a.child)
}

private fun classify(plan: HAction): Map<String, UnitExpectation> {
    val units = linkedMapOf<String, UnitExpectation>()
    units[OUTER_ID] = UnitExpectation(parentId = null, depth = 0, mode = Mode.DEFINITE, throwsInside = computeThrows(plan))
    walk(plan, OUTER_ID, Mode.DEFINITE, inDetached = false, units)
    return units
}

private fun walk(
    a: HAction,
    unitId: String,
    mode: Mode,
    inDetached: Boolean,
    units: MutableMap<String, UnitExpectation>,
) {
    val exp = units.getValue(unitId)
    when (a) {
        is HAction.Increment -> {
            val key = if (inDetached) "det_${a.key}" else a.key
            when {
                // Detached bodies run only after the outer emit (latch), which is also after
                // every inner emit — so their keys land on no line at all.
                inDetached || mode == Mode.NEVER -> exp.never += key
                mode == Mode.RACY -> exp.may += key
                else -> exp.sums.merge(key, a.by, Long::plus)
            }
        }

        is HAction.Sequential -> {
            var m = mode
            a.children.forEach { c ->
                walk(c, unitId, m, inDetached, units)
                // A deterministically-throwing child aborts the tail whether or not the
                // region itself was racy: if the child ran, it threw; if it was cancelled
                // mid-flight, the exception propagates before the tail either way.
                if (computeThrows(c)) m = Mode.NEVER
            }
        }

        is HAction.Throws -> walk(a.child, unitId, mode, inDetached, units)

        is HAction.FanOut -> {
            val throwing = a.branches.count(::computeThrows)
            a.branches.forEach { b ->
                val branchMode = when {
                    mode == Mode.NEVER -> Mode.NEVER
                    throwing == 0 -> mode
                    // The sole throwing branch runs uninterrupted up to its own throw —
                    // nothing else can cancel it, so its (pre-throw) contributions inherit
                    // the surrounding determinism. Its siblings race the cancellation.
                    throwing == 1 && computeThrows(b) -> mode
                    else -> Mode.RACY
                }
                walk(b, unitId, branchMode, inDetached, units)
            }
        }

        is HAction.NestedUnit -> {
            units[a.id] = UnitExpectation(
                parentId = unitId,
                depth = exp.depth + 1,
                mode = mode,
                throwsInside = computeThrows(a.child),
            )
            // Contributions inside route to the inner line only (inner shadows outer); the
            // inner emit happens even when the child throws (Threw line, then rethrow).
            walk(a.child, a.id, mode, inDetached = false, units)
        }

        is HAction.DetachedLaunch -> walk(a.child, unitId, mode, inDetached = true, units)

        is HAction.WithSwitch -> walk(a.child, unitId, mode, inDetached, units)
        is HAction.InScope -> walk(a.child, unitId, mode, inDetached, units)
        is HAction.AsyncAwait -> walk(a.child, unitId, mode, inDetached, units)
        is HAction.BareAsync -> walk(a.child, unitId, mode, inDetached, units)
        is HAction.BlockingBridge -> walk(a.child, unitId, mode, inDetached, units)
        is HAction.ExecutorHop -> walk(a.child, unitId, mode, inDetached, units)
    }
}
