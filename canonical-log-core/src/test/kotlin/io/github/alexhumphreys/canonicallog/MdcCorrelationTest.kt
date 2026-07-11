package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private val mdcAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

/**
 * Pins the MDC `work_unit_id` mirror ([CanonicalLogMdc]): installed for the duration
 * of a work unit by every entry point and hop helper, previous value restored on
 * unwind, innermost unit wins under nesting — always in lockstep with where ambient
 * contributions go.
 */
class MdcCorrelationTest : DescribeSpec({

    afterTest { MDC.remove(CanonicalLogMdc.KEY) }

    describe("withCanonicalLogBlocking") {
        it("puts work_unit_id in MDC for the duration of the block and clears it after") {
            withCanonicalLogBlocking(mdcAdapter, "wu-blocking", { }) {
                MDC.get("work_unit_id") shouldBe "wu-blocking"
            }
            MDC.get("work_unit_id") shouldBe null
        }

        it("restores a pre-existing MDC value on unwind") {
            MDC.put("work_unit_id", "someone-elses")
            withCanonicalLogBlocking(mdcAdapter, "wu", { }) {
                MDC.get("work_unit_id") shouldBe "wu"
            }
            MDC.get("work_unit_id") shouldBe "someone-elses"
        }

        it("restores MDC when the block throws") {
            runCatching {
                withCanonicalLogBlocking(mdcAdapter, "wu", { }) { error("boom") }
            }
            MDC.get("work_unit_id") shouldBe null
        }

        it("nested: MDC shows the innermost unit, outer id restored when it closes") {
            withCanonicalLogBlocking(mdcAdapter, "outer", { }) {
                withCanonicalLogBlocking(mdcAdapter, "inner", { }) {
                    MDC.get("work_unit_id") shouldBe "inner"
                }
                MDC.get("work_unit_id") shouldBe "outer"
            }
        }
    }

    describe("withCanonicalLog (suspend)") {
        it("keeps work_unit_id in MDC across dispatcher switches and clears it after") {
            runBlocking {
                withCanonicalLog(mdcAdapter, "wu-suspend", { }) {
                    MDC.get("work_unit_id") shouldBe "wu-suspend"
                    withContext(Dispatchers.IO) {
                        MDC.get("work_unit_id") shouldBe "wu-suspend"
                    }
                    MDC.get("work_unit_id") shouldBe "wu-suspend"
                }
                MDC.get("work_unit_id") shouldBe null
            }
        }
    }

    describe("propagatingCanonicalContext") {
        // Single worker thread on purpose, same as ContextPropagationTest: the
        // after-probe checks the MDC of the exact thread the wrapped task ran on.
        it("worker thread carries the id during the task and is clean afterwards") {
            val pool = Executors.newFixedThreadPool(1)
            try {
                withCanonicalLogBlocking(mdcAdapter, "wu-pool", { }) {
                    val during = pool.submit(
                        Callable { MDC.get("work_unit_id") }.propagatingCanonicalContext(),
                    ).get()
                    during shouldBe "wu-pool"

                    val after = pool.submit(Callable { MDC.get("work_unit_id") }).get()
                    after shouldBe null
                }
            } finally {
                pool.shutdown()
            }
        }
    }

    describe("CanonicalLogMdc.enabled = false") {
        it("suppresses the mirror entirely") {
            CanonicalLogMdc.enabled = false
            try {
                withCanonicalLogBlocking(mdcAdapter, "wu", { }) {
                    MDC.get("work_unit_id") shouldBe null
                }
                MDC.get("work_unit_id") shouldBe null
            } finally {
                CanonicalLogMdc.enabled = true
            }
        }

        it("never touches a foreign work_unit_id: disabled means hands off MDC, not just no install") {
            CanonicalLogMdc.enabled = false
            MDC.put("work_unit_id", "someone-elses")
            try {
                withCanonicalLogBlocking(mdcAdapter, "wu", { }) {
                    MDC.get("work_unit_id") shouldBe "someone-elses"
                }
                MDC.get("work_unit_id") shouldBe "someone-elses"
            } finally {
                CanonicalLogMdc.enabled = true
            }
        }

        it("never touches a foreign work_unit_id on the suspend emit path either") {
            CanonicalLogMdc.enabled = false
            MDC.put("work_unit_id", "someone-elses")
            try {
                runBlocking {
                    withCanonicalLog(mdcAdapter, "wu", { }) { }
                }
                MDC.get("work_unit_id") shouldBe "someone-elses"
            } finally {
                CanonicalLogMdc.enabled = true
            }
        }
    }
})
