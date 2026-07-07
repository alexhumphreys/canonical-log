package io.github.alexhumphreys.canonicallog.jobrunr

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.JobState
import org.jobrunr.jobs.states.ProcessingState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins `JobRunrWorkUnitAdapter` by driving constructed `Job` instances (pure data — no server)
 * through the real `withCanonicalLogBlocking` lifecycle and asserting on the emitted snapshot.
 * Covers the success line, the `job_attempt` derivation from failed-state history, the thrown /
 * cancelled outcome mappings with their check-before-default reason fields, and the never-capture
 * discipline (identity only; no job arguments).
 */
class JobRunrWorkUnitAdapterTest : DescribeSpec({

    fun jobDetails(): JobDetails =
        JobDetails("io.example.MyJob", null, "run", emptyList())

    // A first-run job: enqueued then processing, no prior failures (attempt 1).
    fun freshJob(name: String = "MyJob.run"): Job {
        val job = Job(jobDetails())
        job.jobName = name
        return job
    }

    // A job with [priorFailures] recorded FAILED states in its history, currently PROCESSING —
    // exactly the shape the filter sees at a terminal callback for attempt (priorFailures + 1).
    fun jobWithPriorFailures(priorFailures: Int, name: String = "MyJob.run"): Job {
        val states = mutableListOf<JobState>()
        repeat(priorFailures) {
            states += EnqueuedState()
            states += ProcessingState(UUID.randomUUID(), "test-server")
            states += FailedState("boom", RuntimeException("boom"))
        }
        states += EnqueuedState()
        states += ProcessingState(UUID.randomUUID(), "test-server")
        val job = Job(UUID.randomUUID(), 0, jobDetails(), states, ConcurrentHashMap())
        job.jobName = name
        return job
    }

    fun run(job: Job, block: () -> Unit = {}): Map<String, Any> {
        var fields: Map<String, Any> = emptyMap()
        try {
            withCanonicalLogBlocking(JobRunrWorkUnitAdapter(), job, emit = { fields = it.snapshot() }) { block() }
        } catch (_: Exception) {
            // captured on the line; assertions read `fields`
        }
        return fields
    }

    describe("JobRunrWorkUnitAdapter") {
        it("emits identity, job id, job name, attempt 1, and a duration on the success path") {
            val job = freshJob(name = "reports.generate")
            val fields = run(job)

            fields["work_unit_kind"] shouldBe "background_job"
            fields["work_unit_id"] shouldBe job.id.toString()
            fields["job_id"] shouldBe job.id.toString()
            fields["job_name"] shouldBe "reports.generate"
            fields["job_attempt"] shouldBe 1L
            fields shouldContainKey "job_duration_ms"
            (fields["job_duration_ms"] is Long) shouldBe true
            fields shouldNotContainKey "error"
            fields shouldNotContainKey "cancelled"
        }

        it("lets the job body contribute fields via CanonicalLog.put") {
            val fields = run(freshJob()) { CanonicalLog.put("rows_processed", 42L) }
            fields["rows_processed"] shouldBe 42L
        }

        it("derives job_attempt from the count of prior FAILED states") {
            run(jobWithPriorFailures(0))["job_attempt"] shouldBe 1L
            run(jobWithPriorFailures(1))["job_attempt"] shouldBe 2L
            run(jobWithPriorFailures(3))["job_attempt"] shouldBe 4L
        }

        it("maps a thrown body to error / error_class / default error_reason") {
            val fields = run(freshJob()) { throw IllegalStateException("kaboom") }

            fields["error"] shouldBe true
            fields["error_class"] shouldBe "java.lang.IllegalStateException"
            fields["error_reason"] shouldBe "exception"
            fields shouldNotContainKey "cancelled"
        }

        it("defers to a handler-set error_reason (check-before-default)") {
            val fields = run(freshJob()) {
                CanonicalLog.markFailed("payment_declined")
                throw IllegalStateException("kaboom")
            }
            fields["error"] shouldBe true
            fields["error_reason"] shouldBe "payment_declined"
        }

        it("maps a cancelled body to cancelled / default cancel_reason, never error") {
            val fields = run(freshJob()) { throw kotlin.coroutines.cancellation.CancellationException("aborted") }

            fields["cancelled"] shouldBe true
            fields["cancel_reason"] shouldBe "cancelled"
            fields shouldNotContainKey "error"
        }
    }
})
