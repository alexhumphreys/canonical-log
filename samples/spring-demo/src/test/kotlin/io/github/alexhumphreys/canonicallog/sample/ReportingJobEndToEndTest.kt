package io.github.alexhumphreys.canonicallog.sample

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext

/**
 * End-to-end test of the second (non-HTTP) entry point: the `@Scheduled` [ReportingJob].
 *
 * Proves that the work-unit lifecycle and `WorkUnitAdapter` generalize past the HTTP
 * filter — a hand-written adapter plus [io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking]
 * emit one canonical line per job run, and the JDBC contributor (unchanged, entry-point
 * agnostic) lands its fields on that line because the query runs on the job's bound thread.
 *
 * The job is off by default; this test boots the app with the enabling flag plus fast
 * timings so a line appears within the poll window.
 */
class ReportingJobEndToEndTest : DescribeSpec({

    var app: ConfigurableApplicationContext? = null
    val appender = ListAppender<ILoggingEvent>()

    beforeSpec {
        app = SpringApplication.run(
            Application::class.java,
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:job-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            "--canonical-log.sample.scheduled-job.enabled=true",
            "--canonical-log.sample.scheduled-job.initial-delay-ms=100",
            "--canonical-log.sample.scheduled-job.fixed-delay-ms=200",
        )

        // Attach the appender AFTER Spring boots — Spring Boot resets logback during
        // initialization, which would clear an earlier attachment (same gotcha as the
        // HTTP end-to-end test).
        appender.start()
        val canonical = LoggerFactory.getLogger("canonical") as LogbackLogger
        canonical.addAppender(appender)
        canonical.level = Level.INFO
    }

    afterSpec {
        app?.close()
        (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
    }

    describe("Scheduled job entry point") {
        it("emits one canonical line per run with job identity, JDBC fields, and a handler field") {
            val snap = awaitScheduledLine(appender)
                ?: error("no scheduled-job canonical line was emitted within the timeout")

            // Job identity — proves the hand-written adapter ran.
            snap["work_unit_kind"] shouldBe "scheduled_job"
            snap["job_name"] shouldBe "daily_report"
            (snap["work_unit_id"] as String).shouldNotBeEmpty()
            (snap["job_duration_ms"] as Long).shouldBeGreaterThanOrEqual(0L)

            // JDBC fields — proves the contributor works in a non-HTTP unit, unchanged.
            (snap["db_query_count"] as Long).shouldBeGreaterThanOrEqual(1L)
            (snap["db_execution_count"] as Long).shouldBeGreaterThanOrEqual(1L)

            // Handler-supplied per-run field — two posts seeded in data.sql.
            snap["report_row_count"] shouldBe 2L

            // A clean run is not an error.
            snap.containsKey("error") shouldBe false
        }
    }
})

/**
 * Poll the appender for the first canonical line whose work unit is a scheduled job, up to a
 * deadline. Returns its field snapshot, or `null` if none appeared in time.
 */
private fun awaitScheduledLine(
    appender: ListAppender<ILoggingEvent>,
    timeoutMs: Long = 5_000,
): Map<String, Any>? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val hit = allCanonicalSnapshots(appender)
            .firstOrNull { it["work_unit_kind"] == "scheduled_job" }
        if (hit != null) return hit
        Thread.sleep(50)
    }
    return null
}
