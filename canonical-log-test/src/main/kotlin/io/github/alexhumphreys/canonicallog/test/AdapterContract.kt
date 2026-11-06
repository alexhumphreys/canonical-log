package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * The behavioral contract every [WorkUnitAdapter] must pass — the sibling of
 * [contributorContract] for code that *owns* the work-unit edges (a message-consumer adapter, a
 * background-job adapter) rather than contributing into a unit someone else opened.
 *
 * The two are separate entry points because the risks differ. An adapter is handed its context,
 * so "no unit bound" can't happen; what can is a hostile input reaching [WorkUnitAdapter.describe]
 * — which core does **not** guard, so a throw there fails the handler before it runs — or one
 * outcome branch of [WorkUnitAdapter.enrich] that only breaks on an input nobody tested. So the
 * cases are the cross product of [inputs] × outcomes:
 *
 * ```kotlin
 * adapterContract(
 *     name = "sqs adapter",
 *     adapter = SqsMessageWorkUnitAdapter("orders"),
 *     inputs = mapOf(
 *         "typical message" to message(),
 *         "no message id" to Message.builder().build(),
 *     ),
 *     expectedFields = mapOf("messaging_system" to String::class, ...),
 *     allowedFields = setOf("messaging_message_id", ...),
 * ).forEach { case -> test(case.name) { case.run() } }
 * ```
 *
 * Cases generated:
 * 1. For **each input × each outcome** (completed, threw, cancelled) the input runs through the
 *    real blocking lifecycle and must come out clean: describe doesn't throw, the block's own
 *    exception is the one that propagates, no `canonical_log_*` diagnostic fires apart from value
 *    truncation (a `canonical_log_seed_error`/`_enrich_error` is the backstop catching a throw), [expectedFields]
 *    land with their types, every field follows the naming conventions, and nothing outside
 *    [expectedFields]/[allowedFields]/[allowedFieldPrefixes] is written.
 * 2. **Type conflict**: with every expected key pre-loaded by "the handler" with a wrong-typed
 *    value, the first input still completes cleanly and each key is overwritten or the conflict
 *    recorded.
 *
 * [expectedFields] are the fields written on **every** line, whatever the input or outcome;
 * fields written only sometimes (an id that's absent on some inputs, the outcome markers `error`,
 * `error_class`, `cancelled`, ...) belong in [allowedFields]. The first entry of [inputs] should
 * be the typical one.
 */
public fun <T> adapterContract(
    name: String,
    adapter: WorkUnitAdapter<T>,
    inputs: Map<String, T>,
    expectedFields: Map<String, KClass<*>>,
    allowedFieldPrefixes: Set<String> = emptySet(),
    allowedFields: Set<String> = emptySet(),
): List<ContractCase> {
    require(inputs.isNotEmpty()) { "an adapter contract needs at least one input" }
    val allowed = FieldAllowance(expectedFields.keys + allowedFields, allowedFieldPrefixes)

    val cases = mutableListOf<ContractCase>()
    inputs.forEach { (label, input) ->
        BlockEnding.entries.forEach { ending ->
            cases += ContractCase("$name: $label, block ${ending.label}, runs clean") {
                verify { problems ->
                    val fields = problems.runAdapter(adapter, input, ending) ?: return@verify
                    problems.checkExpected(fields, expectedFields)
                    problems.checkConventions(fields)
                    problems.checkClean(fields, allowed)
                }
            }
        }
    }
    val (typicalLabel, typical) = inputs.entries.first()
    cases += ContractCase("$name: $typicalLabel survives a handler that wrote conflicting types onto its keys") {
        verify { problems ->
            val fields = problems.runAdapter(adapter, typical, BlockEnding.COMPLETED) { ctx ->
                expectedFields.forEach { (key, type) -> ctx.put(key, if (type == Long::class) POISON else -1L) }
            } ?: return@verify
            problems.checkConflictResolved(fields, expectedFields)
            problems.checkClean(fields, allowed, permitted = TYPE_CONFLICT_FIELDS)
        }
    }
    return cases
}

private enum class BlockEnding(val label: String) {
    COMPLETED("completes"),
    THREW("throws"),
    CANCELLED("is cancelled"),
}

private class SimulatedHandlerFailure : RuntimeException("adapter contract: simulated handler failure")

/**
 * Run [input] through the real blocking lifecycle, ending the block as [ending] says. Returns the
 * emitted fields, or `null` (with the problem recorded) if the lifecycle itself broke.
 */
private fun <T> MutableList<String>.runAdapter(
    adapter: WorkUnitAdapter<T>,
    input: T,
    ending: BlockEnding,
    before: (CanonicalLogContext) -> Unit = {},
): Map<String, Any>? {
    val blockException: Exception? = when (ending) {
        BlockEnding.COMPLETED -> null
        BlockEnding.THREW -> SimulatedHandlerFailure()
        BlockEnding.CANCELLED -> CancellationException("adapter contract: simulated cancellation")
    }
    var fields: Map<String, Any>? = null
    var describeCompleted = false
    val escaped: Exception? = try {
        withCanonicalLogBlocking(adapter, input, emit = { fields = it.snapshot() }) { ctx ->
            describeCompleted = true
            before(ctx)
            if (blockException != null) throw blockException
        }
        null
    } catch (e: Exception) {
        e
    }
    if (!describeCompleted) {
        this += "describe threw ${escaped?.let { "${it::class.qualifiedName}: ${it.message}" }} — core does " +
            "not guard describe, so this fails the handler before it runs"
        return null
    }
    if (escaped !== blockException) {
        this += "the lifecycle threw ${escaped?.let { it::class.qualifiedName }} instead of letting the " +
            "block's own ${blockException?.let { it::class.qualifiedName } ?: "normal completion"} through"
    }
    if (fields == null) {
        this += "no line was emitted"
        return null
    }
    return fields
}
