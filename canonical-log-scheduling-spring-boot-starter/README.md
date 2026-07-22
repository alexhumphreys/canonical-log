# canonical-log-scheduling-spring-boot-starter

A canonical line per `@Scheduled` run, with **no change to the method body**:

```kotlin
dependencies {
    implementation("io.github.alexhumphreys:canonical-log-scheduling-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

```kotlin
@Component
class ReportingJob(private val jdbc: JdbcTemplate) {
    @Scheduled(fixedDelayString = "5000")
    fun generateReport() {
        val rows = jdbc.queryForObject("SELECT count(*) FROM posts", Long::class.java)
        CanonicalLog.put("report_row_count", rows) // db_query_count lands automatically
    }
}
```

Each run emits a line with `work_unit_kind=scheduled_job`,
`job_name=ReportingJob.generateReport`, `job_duration_ms`, anything contributors add
(JDBC/OkHttp work on the scheduler thread as everywhere else), and your handler fields.

## How it works

Hooks Spring's own scheduled-task observation (not AOP): an `ObservationHandler` maps the
observation's open/close/stop callbacks onto the core work-unit lifecycle. Needs only
`@EnableScheduling` — no actuator (a private `ObservationRegistry` is provisioned if the app
has none).

## Customization

- Override job identity/fields with a `WorkUnitAdapter<ScheduledTaskObservationContext>` bean.
- A user `CanonicalLineWriter` bean overrides the sink ([sink chooser](../docs/sinks.md));
  when the umbrella starter is also present, exactly one default writer is registered and
  one user bean overrides both HTTP and scheduled-job lines.
- Opt out with `canonical-log.scheduling.enabled=false`.

For durable/retried background jobs, see [`canonical-log-jobrunr`](../canonical-log-jobrunr/README.md)
(`work_unit_kind=background_job` — deliberately distinct).
