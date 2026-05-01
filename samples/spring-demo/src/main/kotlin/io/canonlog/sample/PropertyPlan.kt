package io.canonlog.sample

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.canonlog.CanonicalLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Plan ADT for the full-stack property test (`PropertyController`).
 *
 * The test generates random `Action` trees, sends them as JSON to
 * `POST /property/run`, and asserts that every leaf increment lands in the
 * canonical log line emitted for that request. This exercises the full
 * pipeline: filter (sync vs async dispatch), bridge, accumulator, emit.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Action.Increment::class, name = "inc"),
    JsonSubTypes.Type(Action.Sequential::class, name = "seq"),
    JsonSubTypes.Type(Action.WithSwitch::class, name = "wctx"),
    JsonSubTypes.Type(Action.InScope::class, name = "scope"),
    JsonSubTypes.Type(Action.FanOut::class, name = "fan"),
    JsonSubTypes.Type(Action.AsyncAwait::class, name = "async"),
)
sealed class Action {
    data class Increment(val key: String, val by: Long) : Action()
    data class Sequential(val children: List<Action>) : Action()
    data class WithSwitch(val to: PlanDispatcher, val child: Action) : Action()
    data class InScope(val child: Action) : Action()
    data class FanOut(val branches: List<Action>) : Action()
    data class AsyncAwait(val on: PlanDispatcher, val child: Action) : Action()
}

enum class PlanDispatcher { IO, DEFAULT, UNCONFINED }

fun PlanDispatcher.asCoroutineDispatcher(): CoroutineDispatcher = when (this) {
    PlanDispatcher.IO -> Dispatchers.IO
    PlanDispatcher.DEFAULT -> Dispatchers.Default
    PlanDispatcher.UNCONFINED -> Dispatchers.Unconfined
}

suspend fun executePlan(action: Action, scope: CoroutineScope) {
    when (action) {
        is Action.Increment -> CanonicalLog.increment(action.key, action.by)

        is Action.Sequential -> action.children.forEach { executePlan(it, scope) }

        is Action.WithSwitch -> withContext(action.to.asCoroutineDispatcher()) {
            executePlan(action.child, this)
        }

        is Action.InScope -> coroutineScope { executePlan(action.child, this) }

        is Action.FanOut -> coroutineScope {
            action.branches.map { branch ->
                async(Dispatchers.IO) { executePlan(branch, this) }
            }.awaitAll()
        }

        is Action.AsyncAwait -> coroutineScope {
            val d = async(action.on.asCoroutineDispatcher()) { executePlan(action.child, this) }
            d.await()
        }
    }
}

/** Pure walk: returns the expected `key -> sum-of-by` contributions for this plan. */
fun collectExpected(action: Action): Map<String, Long> {
    val acc = mutableMapOf<String, Long>()
    fun walk(a: Action) {
        when (a) {
            is Action.Increment -> acc.merge(a.key, a.by) { x, y -> x + y }
            is Action.Sequential -> a.children.forEach(::walk)
            is Action.WithSwitch -> walk(a.child)
            is Action.InScope -> walk(a.child)
            is Action.FanOut -> a.branches.forEach(::walk)
            is Action.AsyncAwait -> walk(a.child)
        }
    }
    walk(action)
    return acc
}
