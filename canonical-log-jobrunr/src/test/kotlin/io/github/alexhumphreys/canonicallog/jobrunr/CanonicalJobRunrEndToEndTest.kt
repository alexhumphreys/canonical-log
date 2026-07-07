package io.github.alexhumphreys.canonicallog.jobrunr

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter
import io.github.alexhumphreys.canonicallog.test.RecordingCanonicalAppender
import io.github.alexhumphreys.canonicallog.test.canonicalFields
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.jobrunr.configuration.JobRunr
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.jobrunr.scheduling.BackgroundJobRequest
import org.jobrunr.server.BackgroundJobServerConfiguration
import org.jobrunr.storage.InMemoryStorageProvider
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The end-to-end proof of the whole design: a **real** `BackgroundJobServer` +
 * `InMemoryStorageProvider`, with [CanonicalJobServerFilter] wired to a real
 * [LogstashCanonicalLineWriter] emitting on the `"canonical"` logger, and lines observed off the
 * *worker thread* with [RecordingCanonicalAppender] (await + defensive snapshot — the
 * producer-thread race a hand-rolled `appender.list` read can't survive).
 *
 * This is what validates the same-thread assumption the filter's ThreadLocal-keyed-scope rests on:
 * if open and terminal did not run on the same worker thread, the lines below would never appear.
 *
 *  - (a) a job whose body calls `CanonicalLog.put` → exactly one `background_job` line with the
 *    job identity, the attempt number, a duration, and the body-contributed field.
 *  - (b) a job that throws once then succeeds on retry → two lines, attempt 1 (`error=true`) and
 *    attempt 2 (success), disambiguated by `job_id` + `job_attempt`.
 */
class CanonicalJobRunrEndToEndTest : DescribeSpec({

    lateinit var appender: RecordingCanonicalAppender

    beforeSpec {
        appender = RecordingCanonicalAppender.attach()
        JobRunr.configure()
            .useStorageProvider(InMemoryStorageProvider())
            .withJobFilter(CanonicalJobServerFilter(LogstashCanonicalLineWriter()))
            .useBackgroundJobServer(
                // A short poll interval keeps the retry case fast; the default is 15s.
                BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration()
                    .andPollInterval(Duration.ofMillis(200)),
            )
            .initialize()
    }

    afterSpec {
        JobRunr.destroy()
        appender.close()
    }

    describe("real BackgroundJobServer") {
        it("emits one background_job line for a successful job, with a body-contributed field") {
            val jobId = BackgroundJobRequest.enqueue(SuccessJobRequest())

            val line = appender.awaitLine(timeoutMs = 15_000) {
                canonicalFields(it)["work_unit_kind"] == "background_job" &&
                    canonicalFields(it)["job_id"] == jobId.toString()
            }

            line["work_unit_kind"] shouldBe "background_job"
            line["job_id"] shouldBe jobId.toString()
            (line["job_name"] as String).shouldNotBeEmpty()
            line["job_attempt"] shouldBe 1L
            (line["job_duration_ms"] as Long).shouldBeGreaterThanOrEqual(0L)
            // The body's CanonicalLog.put landed — the work unit was bound during the method.
            line["handler_field"] shouldBe "handled"
            line.containsKey("error") shouldBe false
        }

        it("emits two lines for a job that fails once then succeeds on retry") {
            RetryJobHandler.attempts.set(0)
            val jobId = BackgroundJobRequest.enqueue(RetryJobRequest())

            val firstAttempt = appender.awaitLine(timeoutMs = 20_000) {
                canonicalFields(it)["job_id"] == jobId.toString() &&
                    canonicalFields(it)["job_attempt"] == 1L
            }
            firstAttempt["error"] shouldBe true
            firstAttempt["job_attempt"] shouldBe 1L

            val secondAttempt = appender.awaitLine(timeoutMs = 20_000) {
                canonicalFields(it)["job_id"] == jobId.toString() &&
                    canonicalFields(it)["job_attempt"] == 2L
            }
            secondAttempt["job_attempt"] shouldBe 2L
            secondAttempt.containsKey("error") shouldBe false
        }
    }
})

/** A JobRequest whose handler simply contributes a field to the canonical line. */
public class SuccessJobRequest : JobRequest {
    override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> = SuccessJobHandler::class.java
}

public class SuccessJobHandler : JobRequestHandler<SuccessJobRequest> {
    @Job(name = "canonical-e2e-success")
    override fun run(jobRequest: SuccessJobRequest) {
        CanonicalLog.put("handler_field", "handled")
    }
}

/** A JobRequest whose handler throws on the first attempt and succeeds on the second. */
public class RetryJobRequest : JobRequest {
    override fun getJobRequestHandler(): Class<out JobRequestHandler<*>> = RetryJobHandler::class.java
}

public class RetryJobHandler : JobRequestHandler<RetryJobRequest> {
    @Job(name = "canonical-e2e-retry", retries = 1)
    override fun run(jobRequest: RetryJobRequest) {
        if (attempts.incrementAndGet() == 1) {
            throw IllegalStateException("boom on first attempt")
        }
    }

    public companion object {
        // JVM-global: the handler is re-instantiated per attempt, so per-instance state won't persist.
        public val attempts: AtomicInteger = AtomicInteger(0)
    }
}
