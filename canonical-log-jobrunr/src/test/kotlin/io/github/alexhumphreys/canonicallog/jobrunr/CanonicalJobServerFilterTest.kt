package io.github.alexhumphreys.canonicallog.jobrunr

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails

/**
 * Drives the [CanonicalJobServerFilter] callbacks directly (no server) to pin its lifecycle
 * mechanics: open → terminal on success and failure, the same-thread ThreadLocal scope discipline,
 * the defensive no-open / phantom-nesting fallbacks, sampler fail-open, and that a throwing enrich
 * still unbinds and clears the ThreadLocal so the worker's next job starts clean.
 */
class CanonicalJobServerFilterTest : DescribeSpec({

    fun job(name: String = "MyJob.run"): Job {
        val j = Job(JobDetails("io.example.MyJob", null, "run", emptyList()))
        j.jobName = name
        return j
    }

    // A writer that records the emitted field snapshots in order.
    class RecordingWriter : CanonicalLineWriter {
        val lines = mutableListOf<Map<String, Any>>()
        override fun write(context: CanonicalLogContext) {
            lines += context.snapshot()
        }
    }

    describe("open + terminal lifecycle") {
        it("emits one line on the success path and binds the work unit during the job") {
            val writer = RecordingWriter()
            val filter = CanonicalJobServerFilter(writer)
            val j = job("emails.send")

            filter.onProcessing(j)
            // Bound on this (worker) thread while the job runs, so contributors and the body land.
            currentCanonicalContext()!!.workUnit.id shouldBe j.id.toString()
            CanonicalLog.put("body_field", "here")
            filter.onProcessingSucceeded(j)

            writer.lines shouldHaveSize 1
            val line = writer.lines.single()
            line["work_unit_kind"] shouldBe "background_job"
            line["job_id"] shouldBe j.id.toString()
            line["job_name"] shouldBe "emails.send"
            line["body_field"] shouldBe "here"
            line shouldNotContainKey "error"
            // Unbound after the terminal callback.
            currentCanonicalContext().shouldBeNull()
        }

        it("emits an error line on the failure path") {
            val writer = RecordingWriter()
            val filter = CanonicalJobServerFilter(writer)
            val j = job()

            filter.onProcessing(j)
            filter.onProcessingFailed(j, IllegalStateException("boom"))

            val line = writer.lines.single()
            line["error"] shouldBe true
            line["error_class"] shouldBe "java.lang.IllegalStateException"
            line["error_reason"] shouldBe "exception"
        }
    }

    describe("defensive posture") {
        it("a terminal callback with no open scope does not throw and emits nothing") {
            val writer = RecordingWriter()
            val filter = CanonicalJobServerFilter(writer)

            filter.onProcessingSucceeded(job())
            filter.onProcessingFailed(job(), RuntimeException("boom"))

            writer.lines shouldHaveSize 0
            currentCanonicalContext().shouldBeNull()
        }

        it("two sequential jobs on one thread do not leak binding or nest") {
            val writer = RecordingWriter()
            val filter = CanonicalJobServerFilter(writer)
            val a = job("job.a")
            val b = job("job.b")

            filter.onProcessing(a)
            filter.onProcessingSucceeded(a)
            filter.onProcessing(b)
            filter.onProcessingSucceeded(b)

            writer.lines shouldHaveSize 2
            writer.lines[0]["job_name"] shouldBe "job.a"
            writer.lines[1]["job_name"] shouldBe "job.b"
            // No phantom nesting: b was opened top-level, not inside a's dead scope.
            writer.lines[1] shouldNotContainKey "parent_work_unit_id"
            writer.lines[1] shouldNotContainKey "work_unit_depth"
            currentCanonicalContext().shouldBeNull()
        }
    }

    describe("sampler") {
        it("a throwing sampler fails open — the line is still written") {
            val writer = RecordingWriter()
            val filter = CanonicalJobServerFilter(
                writer = writer,
                sampler = CanonicalLogSampler { error("sampler blew up") },
            )
            val j = job()

            filter.onProcessing(j)
            filter.onProcessingSucceeded(j)

            writer.lines shouldHaveSize 1
        }

        it("a false sampler suppresses the line but still unbinds") {
            val writer = RecordingWriter()
            val filter = CanonicalJobServerFilter(
                writer = writer,
                sampler = CanonicalLogSampler { false },
            )
            val j = job()

            filter.onProcessing(j)
            filter.onProcessingSucceeded(j)

            writer.lines shouldHaveSize 0
            currentCanonicalContext().shouldBeNull()
        }
    }

    describe("throwing enrich") {
        it("still unbinds and clears the ThreadLocal so the next job starts clean") {
            val writer = RecordingWriter()
            val throwingAdapter = object : WorkUnitAdapter<Job> {
                override fun describe(input: Job): WorkUnit =
                    WorkUnit(input.id.toString(), "background_job", java.time.Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: Job, outcome: Outcome) {
                    throw IllegalStateException("enrich failed")
                }
            }
            val filter = CanonicalJobServerFilter(writer, adapter = throwingAdapter)
            val j = job()

            filter.onProcessing(j)
            filter.onProcessingSucceeded(j)

            // enrich throwing is swallowed-and-recorded by core; the binding is still cleared.
            currentCanonicalContext().shouldBeNull()
            val line = writer.lines.single()
            line["canonical_log_enrich_error"] shouldBe true

            // A subsequent job on the same thread opens a fresh, unnested unit.
            val next = job("next.job")
            filter.onProcessing(next)
            currentCanonicalContext()!!.workUnit.id shouldBe next.id.toString()
            filter.onProcessingSucceeded(next)
            currentCanonicalContext().shouldBeNull()
        }
    }
})
