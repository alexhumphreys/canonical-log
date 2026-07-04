package io.github.alexhumphreys.canonicallog.sample

import io.github.alexhumphreys.canonicallog.CanonicalLog
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * A non-HTTP entry point demonstrating that canonical logging generalizes past the HTTP filter.
 *
 * This is a *plain* `@Scheduled` method — no `withCanonicalLogBlocking`, no hand-written adapter,
 * no writer wiring. The `canonical-log-scheduling-spring-boot-starter` on the classpath
 * instruments every scheduled run transparently (via Spring's own scheduled-task observation),
 * opening a work unit around the method. So:
 *  - the JDBC contributor lands `db_query_count` etc. on the job's line, because the query runs on
 *    the bound scheduler thread — the same contributor the HTTP path uses, unchanged;
 *  - a per-run field (`report_row_count`) is contributed via the ambient API, exactly as a request
 *    handler would;
 *  - the line carries `work_unit_kind=scheduled_job` and `job_name=ReportingJob.generateReport`,
 *    derived mechanically from the method by the starter's adapter.
 *
 * Off by default so it doesn't add background lines to the demo (or to the HTTP tests, which assert
 * on the last canonical line). Enable with `--canonical-log.sample.scheduled-job.enabled=true`;
 * pinned by `ReportingJobEndToEndTest`.
 */
@Component
@ConditionalOnProperty(
    name = ["canonical-log.sample.scheduled-job.enabled"],
    havingValue = "true",
)
class ReportingJob(
    private val jdbc: JdbcTemplate,
) {
    @Scheduled(
        initialDelayString = "\${canonical-log.sample.scheduled-job.initial-delay-ms:1000}",
        fixedDelayString = "\${canonical-log.sample.scheduled-job.fixed-delay-ms:5000}",
    )
    fun generateReport() {
        val postCount = jdbc.queryForObject("SELECT count(*) FROM posts", Long::class.java) ?: 0L
        CanonicalLog.put("report_row_count", postCount)
    }
}
