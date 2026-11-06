package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.CanonicalLogLimits
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.time.Instant
import kotlin.concurrent.thread

// --- Toy contributors: one well-behaved, the rest each breaking exactly one rule ---

private val toyFields = mapOf(
    "toy_call_count" to Long::class,
    "toy_call_duration_ms_total" to Long::class,
)

/** The reference toy: ambient increments only, so it no-ops unbound and drops conflicts. */
private fun wellBehaved() {
    CanonicalLog.increment("toy_call_count")
    CanonicalLog.increment("toy_call_duration_ms_total", 3L)
}

/** Fails its own operation; the contributor counts it and lets the IOException through. */
private fun wellBehavedFailure() {
    CanonicalLog.increment("toy_call_count")
    CanonicalLog.increment("toy_call_duration_ms_total", 3L)
    CanonicalLog.increment("toy_call_error_count")
    throw IOException("connection refused")
}

private fun contract(
    operation: () -> Unit = ::wellBehaved,
    failure: ContractScenario? = failingScenario<IOException>(mapOf("toy_call_error_count" to Long::class)) {
        wellBehavedFailure()
    },
    adversarial: Map<String, ContractScenario> = emptyMap(),
): List<ContractCase> = contributorContract(
    name = "toy",
    operation = operation,
    expectedFields = toyFields,
    allowedFieldPrefixes = setOf("toy_"),
    failure = failure,
    adversarial = adversarial,
)

private fun List<ContractCase>.case(fragment: String): ContractCase = single { fragment in it.name }

private fun List<ContractCase>.shouldFail(fragment: String, message: String) {
    val e = shouldThrow<AssertionError> { case(fragment).run() }
    e.message!! shouldContain message
}

// --- Toy adapters ---

private val toyAdapterFields = mapOf(
    "toy_input" to String::class,
    "toy_process_duration_ms" to Long::class,
)

private val outcomeFields = setOf(
    CanonicalFields.ERROR,
    CanonicalFields.ERROR_CLASS,
    CanonicalFields.ERROR_REASON,
    CanonicalFields.CANCELLED,
    CanonicalFields.CANCEL_REASON,
)

private open class ToyAdapter : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit("toy-$input", "toy", Instant.now())

    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("toy_input", input)
        ctx.put("toy_process_duration_ms", outcome.durationMs)
        when (outcome) {
            is Outcome.Threw -> {
                ctx.put(CanonicalFields.ERROR, true)
                ctx.put(CanonicalFields.ERROR_CLASS, outcome.cause::class.qualifiedName ?: "unknown")
            }
            is Outcome.Cancelled -> ctx.put(CanonicalFields.CANCELLED, true)
            is Outcome.Completed -> Unit
        }
    }
}

private fun adapterContractFor(adapter: WorkUnitAdapter<String>): List<ContractCase> = adapterContract(
    name = "toy adapter",
    adapter = adapter,
    inputs = mapOf("typical" to "order-1", "blank" to ""),
    expectedFields = toyAdapterFields,
    allowedFields = outcomeFields,
)

/**
 * Self-tests for [contributorContract] / [adapterContract] (todo 048): a well-behaved toy passes
 * every case, and each deliberately broken toy fails the case that names its bug — the harness is
 * only worth anything if it catches the throwing contributor it exists for.
 */
class ContributorContractTest : DescribeSpec({

    afterEach {
        CanonicalLog.onUnboundContribution = null
        CanonicalLog.onLateWrite = null
    }

    describe("contributorContract") {
        it("passes every case for a well-behaved contributor") {
            val cases = contract(adversarial = mapOf("noop" to scenario { wellBehaved() }))
            cases shouldHaveSize 6
            shouldNotThrowAny { cases.runAll() }
        }

        it("fails the no-unit-bound case for a contributor that dereferences the binding") {
            contract(operation = {
                currentCanonicalContext()!!.increment("toy_call_count")
                CanonicalLog.increment("toy_call_duration_ms_total", 3L)
            }).shouldFail("no work unit is bound", "NullPointerException")
        }

        it("fails the type-conflict case for a contributor that casts what it reads") {
            contract(operation = {
                val ctx = checkNotNull(currentCanonicalContext())
                val previous = ctx.get("toy_call_count") as Long? ?: 0L
                ctx.put("toy_call_count", previous + 1)
                ctx.increment("toy_call_duration_ms_total", 3L)
            }).shouldFail("type conflict", "ClassCastException")
        }

        it("fails the expected-fields case when a declared field is missing or mistyped") {
            contract(operation = { CanonicalLog.put("toy_call_count", "one") })
                .shouldFail("declared types", "\"toy_call_duration_ms_total\" is missing")
            contract(operation = { CanonicalLog.put("toy_call_count", "one") })
                .shouldFail("declared types", "should be kotlin.Long")
        }

        it("fails the conventions check for an Int duration, a negative count, a false boolean, or a camelCase key") {
            val cases = contract(operation = {
                wellBehaved()
                CanonicalLog.put("toy_wait_ms", 3)
                CanonicalLog.increment("toy_undo_count", -1L)
                CanonicalLog.put("toy_retried", false)
                CanonicalLog.put("toyCamel", "x")
            })
            cases.shouldFail("declared types", "\"toy_undo_count\" is -1; counters and durations are never negative")
            cases.shouldFail("declared types", "\"toy_wait_ms\" ends in _ms so must be a Long")
            cases.shouldFail("declared types", "\"toy_retried\" is false")
            cases.shouldFail("declared types", "\"toyCamel\" is not snake_case")
        }

        it("fails the no-leak case for a field outside the declared prefixes") {
            contract(operation = {
                wellBehaved()
                CanonicalLog.put("authorization", "Bearer secret")
            }).shouldFail("nothing outside", "undeclared field(s) contributed: authorization=")
        }

        it("passes a hostile value the length guardrail truncates (the guardrail doing its job)") {
            val cases = contract(adversarial = mapOf("an oversized value" to scenario {
                wellBehaved()
                CanonicalLog.put("toy_target", "x".repeat(CanonicalLogLimits.DEFAULT_MAX_VALUE_LENGTH + 1))
            }))
            shouldNotThrowAny { cases.case("an oversized value").run() }
        }

        it("fails the no-leak case when the contributor hits the field cap (minting keys)") {
            val previous = CanonicalLog.limits
            CanonicalLog.limits = CanonicalLogLimits(maxFields = 3)
            try {
                contract(operation = {
                    wellBehaved()
                    repeat(5) { i -> CanonicalLog.put("toy_item_$i", "x") }
                }).shouldFail("nothing outside", "library diagnostic fired: canonical_log_dropped_field_count")
            } finally {
                CanonicalLog.limits = previous
            }
        }

        it("fails the no-leak case when a guard swallowed a throw (the backstop firing)") {
            contract(operation = {
                wellBehaved()
                try {
                    error("bug in the contributor")
                } catch (e: IllegalStateException) {
                    CanonicalLog.put(CanonicalFields.CONTRIBUTOR_ERROR, true)
                    CanonicalLog.put(CanonicalFields.CONTRIBUTOR_ERROR_CLASS, e::class.qualifiedName)
                }
            }).shouldFail("nothing outside", "a contributor guard swallowed a java.lang.IllegalStateException")
        }

        it("fails the no-leak case for a contribution lost across an unpropagated thread hop") {
            contract(operation = {
                wellBehaved()
                thread { CanonicalLog.increment("toy_call_count") }.join()
            }).shouldFail("loses nothing", "unbound contribution to \"toy_call_count\"")
        }

        it("fails the failure-path case when telemetry replaces the operation's exception") {
            contract(failure = failingScenario<IOException> {
                try {
                    wellBehavedFailure()
                } catch (e: IOException) {
                    throw IllegalStateException("telemetry bug", e)
                }
            }).shouldFail("failure path", "threw java.lang.IllegalStateException (telemetry bug) instead of its own java.io.IOException")
        }

        it("fails an adversarial case that throws") {
            contract(adversarial = mapOf("hostile name" to scenario { error("boom on hostile name") }))
                .shouldFail("adversarial input: hostile name", "boom on hostile name")
        }

        it("rejects a failure path that doesn't name its exception") {
            shouldThrow<IllegalArgumentException> { contract(failure = scenario { wellBehaved() }) }
        }

        it("reports every failing case at once from runAll") {
            val e = shouldThrow<AssertionError> {
                contract(operation = { currentCanonicalContext()!!.increment("toy_call_count") }).runAll()
            }
            e.message!! shouldContain "contract case(s) failed"
            e.message!! shouldContain "no work unit is bound"
            e.message!! shouldContain "declared types"
        }
    }

    describe("adapterContract") {
        it("passes every case for a well-behaved adapter") {
            val cases = adapterContractFor(ToyAdapter())
            cases shouldHaveSize 7 // 2 inputs × 3 block endings + type conflict
            shouldNotThrowAny { cases.runAll() }
        }

        it("fails when describe throws on a hostile input") {
            adapterContractFor(object : ToyAdapter() {
                override fun describe(input: String): WorkUnit {
                    require(input.isNotEmpty()) { "blank id" }
                    return super.describe(input)
                }
            }).shouldFail("blank, block completes", "describe threw java.lang.IllegalArgumentException: blank id")
        }

        it("fails when enrich throws on one outcome branch (the enrich backstop firing)") {
            val cases = adapterContractFor(object : ToyAdapter() {
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    super.enrich(ctx, input, outcome)
                    if (outcome is Outcome.Cancelled) error("unhandled outcome")
                }
            })
            cases.case("typical, block completes").run()
            cases.shouldFail("typical, block is cancelled", "the adapter's enrich threw java.lang.IllegalStateException")
        }

        it("fails when an adapter writes a field it didn't declare") {
            adapterContractFor(object : ToyAdapter() {
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    super.enrich(ctx, input, outcome)
                    ctx.put("message_body", "PII")
                }
            }).shouldFail("typical, block completes", "undeclared field(s) contributed: message_body=")
        }
    }

    describe("negative assertions") {
        it("hasNoField passes on absence and names the value on presence") {
            mapOf<String, Any>("a" to 1L).hasNoField("b")
            val e = shouldThrow<AssertionError> { mapOf<String, Any>("error" to true).hasNoField("error") }
            e.message shouldBe "expected no field \"error\", but it was present with value true (kotlin.Boolean)"
        }

        it("hasNoFieldMatching lists every offending key") {
            mapOf<String, Any>("db_query_count" to 1L).hasNoFieldMatching(Regex("^http_"))
            val e = shouldThrow<AssertionError> {
                mapOf<String, Any>("http_a" to "x", "http_b" to 2L, "db" to 1L).hasNoFieldMatching(Regex("^http_"))
            }
            e.message!! shouldContain "http_a=x (kotlin.String), http_b=2 (kotlin.Long)"
        }

        it("works on a captured line") {
            val line = captureCanonicalLineBlocking { CanonicalLog.put("kept", "x") }
            line.hasNoField(CanonicalFields.ERROR)
            shouldThrow<AssertionError> { line.hasNoFieldMatching(Regex("^kept$")) }
        }
    }
})
