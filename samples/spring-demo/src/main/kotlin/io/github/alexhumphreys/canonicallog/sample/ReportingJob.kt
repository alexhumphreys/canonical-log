package io.github.alexhumphreys.canonicallog.sample

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.spring.LogstashCanonicalLineWriter
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * A non-HTTP entry point: a `@Scheduled` job that opens a canonical work unit by hand.
 *
 * This is the second entry point (todo 009) — it exists to validate that `WorkUnitAdapter`
 * and the work-unit lifecycle generalize past the HTTP filter. The job:
 *  - opens the unit with [withCanonicalLogBlocking] and a hand-written [ScheduledJobAdapter];
 *  - runs a JDBC query inside the block, so the **JDBC contributor** (which resolves the work
 *    unit off the calling thread) lands `db_query_count` etc. on the job's line with no extra
 *    wiring — the same contributor the HTTP path uses, proving contributors are entry-point
 *    agnostic;
 *  - contributes a per-run field (`report_row_count`) via the ambient API, exactly as a
 *    request handler would.
 *
 * **What a job author hand-rolls that the HTTP filter gets for free** (recorded as follow-up
 * in `docs/todos/019-job-entry-point-ergonomics.md`): the emit sink. The starter's
 * auto-config resolves a `CanonicalLineWriter` (user-bean override, logstash default) and
 * injects it into the filter, but exposes no injectable writer bean — so here we construct a
 * [LogstashCanonicalLineWriter] directly and pass `writer::write` as the emit function. MDC
 * correlation, by contrast, is free: [withCanonicalLogBlocking] installs `work_unit_id` into
 * MDC itself.
 *
 * Off by default so it doesn't add background lines to the demo (or to the HTTP tests, which
 * assert on the last canonical line). Enable it with
 * `--canonical-log.sample.scheduled-job.enabled=true`; pinned by `ReportingJobEndToEndTest`.
 */
@Component
@ConditionalOnProperty(
    name = ["canonical-log.sample.scheduled-job.enabled"],
    havingValue = "true",
)
class ReportingJob(
    private val jdbc: JdbcTemplate,
) {
    // No injectable CanonicalLineWriter bean exists (see the class KDoc); construct the
    // default logstash sink so the job's line flows through the same "canonical" logger
    // the HTTP filter uses.
    private val writer = LogstashCanonicalLineWriter()
    private val adapter = ScheduledJobAdapter()

    @Scheduled(
        initialDelayString = "\${canonical-log.sample.scheduled-job.initial-delay-ms:1000}",
        fixedDelayString = "\${canonical-log.sample.scheduled-job.fixed-delay-ms:5000}",
    )
    fun generateReport() {
        withCanonicalLogBlocking(adapter, JOB_NAME, writer::write) {
            val postCount = jdbc.queryForObject("SELECT count(*) FROM posts", Long::class.java) ?: 0L
            CanonicalLog.put("report_row_count", postCount)
        }
    }

    companion object {
        const val JOB_NAME = "daily_report"
    }
}
