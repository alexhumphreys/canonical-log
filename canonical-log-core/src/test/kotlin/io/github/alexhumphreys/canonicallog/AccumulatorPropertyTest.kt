package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pair
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

private val nullAdapterProp = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

class AccumulatorPropertyTest : DescribeSpec({

    beforeSpec {
        PropertyTesting.defaultIterationCount = 200
        installCoroutineLeakProbe()
    }
    afterSpec { uninstallCoroutineLeakProbe() }

    describe("Accumulator concurrency invariants") {

        it("sum invariant: parallel increments to random fields sum to the total of contributions") {
            val fieldName = Arb.element("a", "b", "c", "d")
            val incrementBy = Arb.long(1L..1000L)
            val contributions = Arb.list(Arb.pair(fieldName, incrementBy), 0..200)
            val baselineJobs = captureBaselineJobs()

            checkAll(contributions) { ops ->
                var snap: Map<String, Any> = emptyMap()
                runBlocking {
                    withCanonicalLog(nullAdapterProp, "wu", { snap = it.snapshot() }) {
                        coroutineScope {
                            ops.map { (field, by) ->
                                async(Dispatchers.IO) { CanonicalLog.increment(field, by) }
                            }.awaitAll()
                        }
                    }
                }

                val expected: Map<String, Long> = ops
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, values) -> values.sum() }

                expected.forEach { (k, v) -> snap[k] shouldBe v }
                // Negative assertion: no extra fields appear
                snap.keys shouldBe expected.keys
                assertNoLeakedCoroutines(baselineJobs)
            }
        }
    }

    describe("Bridge propagation invariants") {

        it("every contribution in an arbitrary nested coroutine structure lands in the snapshot") {
            val baselineJobs = captureBaselineJobs()

            checkAll(arbAction(maxDepth = 3)) { plan ->
                var snap: Map<String, Any> = emptyMap()
                runBlocking {
                    withCanonicalLog(nullAdapterProp, "wu", { snap = it.snapshot() }) {
                        runAction(plan, this)
                    }
                }

                val expected: Map<String, Long> = collectExpected(plan)

                expected.forEach { (k, v) -> snap[k] shouldBe v }
                snap.keys shouldBe expected.keys
                assertNoLeakedCoroutines(baselineJobs)
            }
        }
    }
})

/**
 * A random plan describing how contributions are distributed across coroutine
 * structures. Each leaf [Increment] adds a known amount to a known field. Every
 * other variant wraps a sub-plan in a structure whose semantics SHOULD preserve
 * propagation of the canonical context. The property under test is: regardless
 * of the plan's shape, the snapshot at emit time contains every contribution.
 */
private sealed class Action {
    data class Increment(val key: String, val by: Long) : Action()
    data class Sequential(val children: List<Action>) : Action()
    data class WithSwitch(val to: Dispatcher, val child: Action) : Action()
    data class InScope(val child: Action) : Action()
    data class FanOut(val branches: List<Action>) : Action()
    data class AsyncAwait(val on: Dispatcher, val child: Action) : Action()
    /**
     * Bare `async { ... }` against the parent scope, with no wrapping `coroutineScope`.
     * This exercises the path that broke in the recent regression where
     * [withCanonicalLog]'s block lost its `CoroutineScope` receiver — without the
     * receiver, bare `async` resolves to the test runner's outer scope (which has
     * no canonical element), and contributions inside silently vanish. With the
     * receiver, bare `async` resolves to the entry-point's own scope, which carries
     * the canonical element. This variant ensures we keep covering that path.
     */
    data class BareAsync(val on: Dispatcher, val child: Action) : Action()
}

private enum class Dispatcher { IO, DEFAULT, UNCONFINED }

private fun Dispatcher.asCoroutineDispatcher(): CoroutineDispatcher = when (this) {
    Dispatcher.IO -> Dispatchers.IO
    Dispatcher.DEFAULT -> Dispatchers.Default
    Dispatcher.UNCONFINED -> Dispatchers.Unconfined
}

private fun arbAction(maxDepth: Int): Arb<Action> {
    val keys = Arb.element("a", "b", "c", "d")
    val amounts = Arb.long(1L..100L)
    val dispatchers = Arb.element(Dispatcher.IO, Dispatcher.DEFAULT, Dispatcher.UNCONFINED)

    val leaf: Arb<Action> = Arb.bind(keys, amounts) { k, n -> Action.Increment(k, n) }

    if (maxDepth <= 0) return leaf

    val child = arbAction(maxDepth - 1)
    val sequential: Arb<Action> = Arb.list(child, 0..4).map { Action.Sequential(it) }
    val withSwitch: Arb<Action> = Arb.bind(dispatchers, child) { d, c -> Action.WithSwitch(d, c) }
    val inScope: Arb<Action> = child.map { Action.InScope(it) }
    val fanOut: Arb<Action> = Arb.list(child, 1..4).map { Action.FanOut(it) }
    val asyncAwait: Arb<Action> = Arb.bind(dispatchers, child) { d, c -> Action.AsyncAwait(d, c) }
    val bareAsync: Arb<Action> = Arb.bind(dispatchers, child) { d, c -> Action.BareAsync(d, c) }

    return Arb.choice(leaf, sequential, withSwitch, inScope, fanOut, asyncAwait, bareAsync)
}

private suspend fun runAction(action: Action, scope: CoroutineScope) {
    when (action) {
        is Action.Increment -> CanonicalLog.increment(action.key, action.by)

        is Action.Sequential -> action.children.forEach { runAction(it, scope) }

        is Action.WithSwitch -> withContext(action.to.asCoroutineDispatcher()) {
            runAction(action.child, this)
        }

        is Action.InScope -> coroutineScope { runAction(action.child, this) }

        is Action.FanOut -> coroutineScope {
            action.branches.map { branch ->
                async(Dispatchers.IO) { runAction(branch, this) }
            }.awaitAll()
        }

        is Action.AsyncAwait -> coroutineScope {
            val d = async(action.on.asCoroutineDispatcher()) { runAction(action.child, this) }
            d.await()
        }

        is Action.BareAsync -> {
            val d = scope.async(action.on.asCoroutineDispatcher()) { runAction(action.child, this) }
            d.await()
        }
    }
}

private fun collectExpected(action: Action): Map<String, Long> {
    val acc = mutableMapOf<String, Long>()
    fun walk(a: Action) {
        when (a) {
            is Action.Increment -> acc.merge(a.key, a.by) { x, y -> x + y }
            is Action.Sequential -> a.children.forEach(::walk)
            is Action.WithSwitch -> walk(a.child)
            is Action.InScope -> walk(a.child)
            is Action.FanOut -> a.branches.forEach(::walk)
            is Action.AsyncAwait -> walk(a.child)
            is Action.BareAsync -> walk(a.child)
        }
    }
    walk(action)
    return acc
}
