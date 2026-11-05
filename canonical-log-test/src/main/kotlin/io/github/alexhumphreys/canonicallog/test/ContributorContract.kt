package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import kotlin.reflect.KClass

/**
 * One named, independently runnable case of a contract built by [contributorContract] or
 * [adapterContract]. [run] returns normally when the case holds and throws [AssertionError]
 * (listing every violation it found) when it doesn't.
 *
 * Contracts hand back a list of cases rather than running themselves so each one reports as
 * its own test, under whatever framework the module uses — the test kit carries no Kotest or
 * JUnit types:
 * ```kotlin
 * class OkHttpContractTest : FunSpec({
 *     contributorContract(...).forEach { case -> test(case.name) { case.run() } }
 * })
 * ```
 * [runAll] runs a whole list in one go, for a single JUnit `@Test`.
 */
public class ContractCase internal constructor(
    /** Human-readable case name, prefixed with the contract's name. */
    public val name: String,
    private val body: () -> Unit,
) {
    /** Run the case; throws [AssertionError] if the contract is violated. */
    public fun run(): Unit = body()

    override fun toString(): String = name
}

/**
 * Run every case, then fail once with all the failures (each attached as a suppressed
 * exception) rather than stopping at the first.
 */
public fun List<ContractCase>.runAll() {
    val failures = mapNotNull { case ->
        try {
            case.run()
            null
        } catch (e: AssertionError) {
            case to e
        } catch (e: Exception) {
            case to AssertionError("case threw ${e::class.qualifiedName}: ${e.message}", e)
        }
    }
    if (failures.isEmpty()) return
    throw AssertionError(
        "${failures.size} of $size contract case(s) failed:\n" +
            failures.joinToString("\n") { (case, e) -> " - ${case.name}: ${e.message}" },
    ).apply { failures.forEach { addSuppressed(it.second) } }
}

/**
 * One way of driving a contributor's instrumented operation, for [contributorContract]'s
 * `failure` and `adversarial` arguments. Build with [scenario] or [failingScenario].
 */
public class ContractScenario internal constructor(
    internal val operation: () -> Unit,
    internal val expectedException: KClass<out Throwable>?,
    internal val expectedFields: Map<String, KClass<*>>,
)

/**
 * A scenario whose [operation] must complete normally, contributing at least [expectedFields]
 * (key → value type). For hostile inputs that the instrumented operation itself tolerates.
 */
public fun scenario(
    expectedFields: Map<String, KClass<*>> = emptyMap(),
    operation: () -> Unit,
): ContractScenario = ContractScenario(operation, null, expectedFields)

/**
 * A scenario whose [operation] must throw [expectedException] — the operation's *own* failure
 * (a refused connection, a failed ack), which the contributor has to let through untouched while
 * still contributing [expectedFields]. Any other exception fails the case: that is telemetry
 * replacing the real error.
 */
public fun failingScenario(
    expectedException: KClass<out Throwable>,
    expectedFields: Map<String, KClass<*>> = emptyMap(),
    operation: () -> Unit,
): ContractScenario = ContractScenario(operation, expectedException, expectedFields)

/** Reified form of [failingScenario]: `failingScenario<IOException>(fields) { ... }`. */
public inline fun <reified E : Throwable> failingScenario(
    expectedFields: Map<String, KClass<*>> = emptyMap(),
    noinline operation: () -> Unit,
): ContractScenario = failingScenario(E::class, expectedFields, operation)

/**
 * The behavioral contract every **contributor** must pass — anything that writes into the work
 * unit bound to the current thread from inside the application's own operation (an HTTP-client
 * interceptor, a JDBC listener, a producer decorator, a resilience event consumer).
 *
 * "Contributors must not throw" is a documented rule with a swallow-and-record backstop
 * ([CanonicalFields.CONTRIBUTOR_ERROR]) but no per-contributor enforcement; this is the
 * enforcement. Describe the contributor once and register the cases it returns:
 *
 * ```kotlin
 * contributorContract(
 *     name = "okhttp interceptor",
 *     operation = { server.enqueue(MockResponse(200)); client.get(server.url("/")) },
 *     expectedFields = mapOf(CanonicalFields.HTTP_CLIENT_REQUEST_COUNT to Long::class, ...),
 *     allowedFieldPrefixes = setOf("http_client_"),
 *     failure = failingScenario<IOException>(mapOf(CanonicalFields.HTTP_CLIENT_ERROR_COUNT to Long::class)) {
 *         client.get(unreachableUrl)
 *     },
 *     adversarial = mapOf("hostile header value" to scenario { ... }),
 * ).forEach { case -> test(case.name) { case.run() } }
 * ```
 *
 * [operation] drives the **real** instrumented operation and must be repeatable — it runs once
 * per case, sometimes with no work unit bound. The harness owns the binding: it never calls
 * [operation] with a unit already open on the test thread, so don't wrap the contract in one.
 *
 * Cases generated:
 * 1. **Expected fields land** with their declared types, and every contributed field follows
 *    the naming conventions (snake_case; `_count`/`_ms`/`_ms_total` keys hold non-negative `Long`s; booleans
 *    only ever `true`, omitted when false; values are strings, numbers or booleans).
 * 2. **Nothing leaks**: every contributed key is in [expectedFields]/[allowedFields] or starts
 *    with one of [allowedFieldPrefixes]; no `canonical_log_*` diagnostic fires other than
 *    value truncation (the length guardrail doing its job on a hostile value), and a
 *    `canonical_log_contributor_error` means a guard swallowed a throw — the backstop firing is a
 *    failure, not a pass; and no contribution is lost (no unbound or late write while a unit
 *    was bound — see [recordLostContributions]).
 * 3. **No throw escapes**, against adversarial contexts: with **no unit bound** (must no-op), and
 *    with every expected `Long` key already holding a `String` (**type conflict** — must be
 *    dropped and recorded per the `increment` rule, or overwritten), plus each [adversarial]
 *    scenario the module supplies.
 * 4. **Failure path** ([failure], when given): the operation's own exception propagates
 *    unchanged and the failure fields still land.
 *
 * Mechanism, not policy: the harness checks types, prefixes, no-throw and no-leak; which fields
 * a contributor writes stays the module's own declaration.
 *
 * The cases install [CanonicalLog][io.github.alexhumphreys.canonicallog.CanonicalLog]'s
 * process-wide diagnostic hooks while they run (restored afterwards), so don't run them in
 * parallel with other hook-installing tests.
 */
public fun contributorContract(
    name: String,
    operation: () -> Unit,
    expectedFields: Map<String, KClass<*>>,
    allowedFieldPrefixes: Set<String> = emptySet(),
    allowedFields: Set<String> = emptySet(),
    failure: ContractScenario? = null,
    adversarial: Map<String, ContractScenario> = emptyMap(),
): List<ContractCase> {
    require(expectedFields.isNotEmpty()) { "a contributor contract needs at least one expected field" }
    require(failure == null || failure.expectedException != null) {
        "the failure path must name the exception the operation throws — use failingScenario"
    }
    val allowed = FieldAllowance(
        fields = expectedFields.keys + allowedFields + failure?.expectedFields?.keys.orEmpty() +
            adversarial.values.flatMap { it.expectedFields.keys },
        prefixes = allowedFieldPrefixes,
    )
    val longKeys = expectedFields.filterValues { it == Long::class }.keys

    val cases = mutableListOf<ContractCase>()
    cases += ContractCase("$name: expected fields land with their declared types") {
        verify { problems ->
            val run = runBound(operation)
            problems.checkNoThrow(run.line.exception)
            problems.checkExpected(run.line.fields, expectedFields)
            problems.checkConventions(run.line.fields)
        }
    }
    cases += ContractCase("$name: contributes nothing outside its declared fields and loses nothing") {
        verify { problems ->
            val run = runBound(operation)
            problems.checkNoThrow(run.line.exception)
            problems.checkClean(run.line.fields, allowed, run.lost)
        }
    }
    cases += ContractCase("$name: no-ops without throwing when no work unit is bound") {
        verify { problems ->
            check(currentCanonicalContext() == null) {
                "a work unit is already bound on the test thread — run the contract outside any unit"
            }
            var thrown: Exception? = null
            // Recording, not failing: an ambient contributor reporting an unbound write is the
            // expected behaviour here, and an outer failOn… wrapper must not turn it into a throw.
            recordLostContributions {
                try {
                    operation()
                } catch (e: Exception) {
                    thrown = e
                }
            }
            problems.checkNoThrow(thrown)
        }
    }
    if (longKeys.isNotEmpty()) {
        cases += ContractCase("$name: survives a type conflict on its own counter keys") {
            verify { problems ->
                val run = runBound(operation, before = { ctx -> longKeys.forEach { ctx.put(it, POISON) } })
                problems.checkNoThrow(run.line.exception)
                problems.checkConflictResolved(run.line.fields, longKeys.associateWith { Long::class })
                problems.checkClean(run.line.fields, allowed, run.lost, permitted = TYPE_CONFLICT_FIELDS)
                problems.checkConventions(run.line.fields, exempt = longKeys)
            }
        }
    }
    if (failure != null) {
        cases += ContractCase("$name: failure path contributes and rethrows the operation's own exception") {
            verify { problems -> problems.checkScenario(runBound(failure.operation), failure, allowed) }
        }
    }
    adversarial.forEach { (label, scenario) ->
        cases += ContractCase("$name: survives adversarial input: $label") {
            verify { problems -> problems.checkScenario(runBound(scenario.operation), scenario, allowed) }
        }
    }
    return cases
}

// --- Shared machinery (also used by adapterContract) ---

internal const val POISON: String = "contract-type-conflict-poison"

internal val TYPE_CONFLICT_FIELDS: Set<String> = setOf(
    CanonicalFields.TYPE_CONFLICT,
    CanonicalFields.TYPE_CONFLICT_KEY,
    CanonicalFields.TYPE_CONFLICT_TYPE,
)

private const val DIAGNOSTIC_PREFIX = "canonical_log_"
private val SNAKE_CASE = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
private val LONG_SUFFIXES = listOf("_count", "_ms", "_ms_total")

internal class FieldAllowance(val fields: Set<String>, val prefixes: Set<String>) {
    fun admits(key: String): Boolean = key in fields || prefixes.any { key.startsWith(it) }
}

private class BoundRun(val line: CapturedCanonicalLine<Unit>, val lost: List<LostContribution>)

private fun runBound(operation: () -> Unit, before: (CanonicalLogContext) -> Unit = {}): BoundRun {
    var line: CapturedCanonicalLine<Unit>? = null
    val lost = recordLostContributions {
        line = captureCanonicalLineBlocking { ctx ->
            before(ctx)
            operation()
        }
    }
    return BoundRun(checkNotNull(line), lost)
}

/** Collect every violation a case finds, then fail once listing them all. */
internal fun verify(body: (MutableList<String>) -> Unit) {
    val problems = mutableListOf<String>()
    var cause: Throwable? = null
    try {
        body(problems)
    } catch (e: Exception) {
        problems += "the harness itself failed: ${e::class.qualifiedName}: ${e.message}"
        cause = e
    }
    if (problems.isNotEmpty()) {
        throw AssertionError(problems.joinToString("\n  ", prefix = "contract violated:\n  "), cause)
    }
}

private fun MutableList<String>.checkScenario(run: BoundRun, scenario: ContractScenario, allowed: FieldAllowance) {
    val expected = scenario.expectedException
    val thrown = run.line.exception
    when {
        expected == null -> checkNoThrow(thrown)
        thrown == null -> this += "expected the operation to throw ${expected.qualifiedName}, but it completed"
        !expected.java.isInstance(thrown) -> this +=
            "the operation threw ${thrown::class.qualifiedName} (${thrown.message}) instead of its own " +
            "${expected.qualifiedName} — telemetry must let the real failure through untouched"
    }
    checkExpected(run.line.fields, scenario.expectedFields)
    checkConventions(run.line.fields)
    checkClean(run.line.fields, allowed, run.lost)
}

internal fun MutableList<String>.checkNoThrow(thrown: Throwable?) {
    if (thrown != null) {
        this += "an exception escaped: ${thrown::class.qualifiedName}: ${thrown.message} " +
            "(telemetry must never fail the operation it observes)"
    }
}

internal fun MutableList<String>.checkExpected(fields: Map<String, Any>, expected: Map<String, KClass<*>>) {
    expected.forEach { (key, type) ->
        val value = fields[key]
        when {
            value == null -> this += "expected field \"$key\" is missing"
            !type.javaObjectType.isInstance(value) ->
                this += "field \"$key\" should be ${type.qualifiedName} but is ${describeValue(value)}"
        }
    }
}

/**
 * The mechanical field conventions from `docs/CLAUDE.md`: snake_case names, `Long` counters and
 * integer-ms durations by suffix, booleans omitted when false, scalar values only.
 */
internal fun MutableList<String>.checkConventions(fields: Map<String, Any>, exempt: Set<String> = emptySet()) {
    fields.forEach { (key, value) ->
        if (key in exempt) return@forEach
        if (!SNAKE_CASE.matches(key)) this += "field \"$key\" is not snake_case"
        if (value !is CharSequence && value !is Number && value !is Boolean) {
            this += "field \"$key\" holds ${describeValue(value)}; values must be strings, numbers or booleans"
        }
        val suffix = LONG_SUFFIXES.firstOrNull { key.endsWith(it) }
        if (suffix != null && value !is Long) {
            this += "field \"$key\" ends in $suffix so must be a Long, but is ${describeValue(value)}"
        }
        if (suffix != null && value is Long && value < 0) {
            this += "field \"$key\" is $value; counters and durations are never negative"
        }
        if (value == false) this += "field \"$key\" is false; booleans are omitted when false, never written"
    }
}

/**
 * Diagnostics that never fail a case: the value-length guardrail cutting a hostile value short is
 * [CanonicalLogLimits][io.github.alexhumphreys.canonicallog.CanonicalLogLimits] doing its job, not
 * a contributor bug — and treating it as one would push modules into testing only short hostile
 * inputs. The field-cap markers (`canonical_log_dropped_field_*`) are *not* here: a contributor
 * that hits the cap is minting keys, which is exactly the creep the no-leak check exists for.
 */
private val ALWAYS_PERMITTED: Set<String> = setOf(
    CanonicalFields.TRUNCATED_FIELD_COUNT,
    CanonicalFields.TRUNCATED_FIELD_KEY,
)

/**
 * No leak, no diagnostics, no lost contributions. [permitted] diagnostics are the ones a case
 * provokes on purpose (the type-conflict markers), on top of [ALWAYS_PERMITTED].
 */
internal fun MutableList<String>.checkClean(
    fields: Map<String, Any>,
    allowed: FieldAllowance,
    lost: List<LostContribution> = emptyList(),
    permitted: Set<String> = emptySet(),
) {
    fields.keys.filter { it.startsWith(DIAGNOSTIC_PREFIX) && it !in permitted && it !in ALWAYS_PERMITTED }.sorted().forEach { key ->
        this += when (key) {
            CanonicalFields.CONTRIBUTOR_ERROR -> "a contributor guard swallowed a " +
                "${fields[CanonicalFields.CONTRIBUTOR_ERROR_CLASS]} — the backstop fired, so the contributor " +
                "has a throwing edge case"
            CanonicalFields.ENRICH_ERROR -> "the adapter's enrich threw ${fields[CanonicalFields.ENRICH_ERROR_CLASS]}"
            CanonicalFields.SEED_ERROR -> "the adapter's seed threw ${fields[CanonicalFields.SEED_ERROR_CLASS]}"
            CanonicalFields.CONTRIBUTOR_ERROR_CLASS, CanonicalFields.ENRICH_ERROR_CLASS,
            CanonicalFields.SEED_ERROR_CLASS,
            -> return@forEach // reported with its flag above
            else -> "library diagnostic fired: $key=${describeValue(fields[key])}"
        }
    }
    val leaked = fields.keys.filter { !it.startsWith(DIAGNOSTIC_PREFIX) && !allowed.admits(it) }.sorted()
    if (leaked.isNotEmpty()) {
        this += "undeclared field(s) contributed: " + leaked.joinToString { "$it=${describeValue(fields[it])}" } +
            " — declare them, or stop writing them"
    }
    lost.forEach { this += "contribution lost while a unit was bound: $it" }
}

/**
 * After [poisoned] keys were pre-loaded with a wrong-typed value: each must now hold its declared
 * type (overwritten via `put`) or still hold the poison with the conflict recorded (an `increment`
 * dropped per the accumulator rule).
 */
internal fun MutableList<String>.checkConflictResolved(fields: Map<String, Any>, poisoned: Map<String, KClass<*>>) {
    poisoned.forEach { (key, type) ->
        val value = fields[key]
        val overwritten = value != null && type.javaObjectType.isInstance(value)
        val recorded = fields[CanonicalFields.TYPE_CONFLICT] == true
        if (!overwritten && !recorded) {
            this += "field \"$key\" was pre-loaded with a conflicting value and ended as ${describeValue(value)} " +
                "with no ${CanonicalFields.TYPE_CONFLICT} recorded"
        }
    }
}
