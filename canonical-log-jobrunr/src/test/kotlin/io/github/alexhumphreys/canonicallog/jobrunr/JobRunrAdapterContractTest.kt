package io.github.alexhumphreys.canonicallog.jobrunr

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogLimits
import io.github.alexhumphreys.canonicallog.test.adapterContract
import io.kotest.core.spec.style.DescribeSpec
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.JobState
import org.jobrunr.jobs.states.ProcessingState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared adapter contract (todo 048) for [JobRunrWorkUnitAdapter]. Additive to
 * [JobRunrWorkUnitAdapterTest] (field semantics) and the filter/e2e suites (lifecycle wiring).
 */
class JobRunrAdapterContractTest : DescribeSpec({

    fun jobDetails(): JobDetails = JobDetails("io.example.MyJob", null, "run", emptyList())

    fun job(name: String? = "MyJob.run", priorFailures: Int = 0): Job {
        val states = mutableListOf<JobState>()
        repeat(priorFailures) {
            states += EnqueuedState()
            states += ProcessingState(UUID.randomUUID(), "test-server")
            states += FailedState("boom", RuntimeException("boom"))
        }
        states += EnqueuedState()
        states += ProcessingState(UUID.randomUUID(), "test-server")
        return Job(UUID.randomUUID(), 0, jobDetails(), states, ConcurrentHashMap()).also { it.jobName = name }
    }

    describe("JobRunrWorkUnitAdapter adapter contract") {
        adapterContract(
            name = "jobrunr adapter",
            adapter = JobRunrWorkUnitAdapter(),
            inputs = mapOf(
                "a first-run job" to job(),
                "a job on its third attempt" to job(priorFailures = 2),
                "a job with no name" to job(name = null),
                "a job with a hostile name" to job(name = "run\n\"x\"=y job_name=spoofed"),
                // Past the value-length limit: truncated (and counted) by the guardrail, still clean.
                "a job with an oversized name" to job(name = "x".repeat(CanonicalLogLimits.DEFAULT_MAX_VALUE_LENGTH * 2)),
                "a freshly constructed job with no state history" to Job(jobDetails()),
            ),
            expectedFields = mapOf(
                CanonicalFields.WORK_UNIT_ID to String::class,
                CanonicalFields.WORK_UNIT_KIND to String::class,
                CanonicalFields.JOB_ID to String::class,
                CanonicalFields.JOB_ATTEMPT to Long::class,
                JobRunrWorkUnitAdapter.JOB_DURATION_MS to Long::class,
            ),
            allowedFields = setOf(
                // Absent when the job carries no name — a null put is dropped.
                CanonicalFields.JOB_NAME,
                CanonicalFields.ERROR,
                CanonicalFields.ERROR_CLASS,
                CanonicalFields.ERROR_REASON,
                CanonicalFields.CANCELLED,
                CanonicalFields.CANCEL_REASON,
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
