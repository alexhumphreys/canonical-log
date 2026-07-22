# canonical-log-jobrunr

A canonical line per [JobRunr](https://www.jobrunr.io/) job **processing attempt**, with no
change to any job body — register the filter once on the server:

```kotlin
JobRunr.configure()
    .useStorageProvider(storageProvider)
    .useBackgroundJobServer(
        usingStandardBackgroundJobServerConfiguration(),
        CanonicalJobServerFilter(writer = LogstashCanonicalLineWriter()),
    )
    .initialize()
```

Each attempt emits `work_unit_kind=background_job` (deliberately distinct from the
scheduling starter's `scheduled_job` — a durable retried job and a `@Scheduled` tick should
be queryably different), `job_id` (joins back to JobRunr's dashboard), `job_name`,
`job_attempt` (1-based; retries are separate work units and separate lines),
`job_duration_ms`, and the standard outcome fields ([docs/fields.md](../docs/fields.md)).
Contributors (JDBC etc.) and `CanonicalLog.put(...)` work inside job methods as everywhere
else. A deleted/aborted job surfaces as a cancellation (`cancelled=true`), not an error;
`error_class` is the job's real exception (reflective wrappers unwrapped).

JobRunr OSS is `compileOnly` — only the public `JobServerFilter` callbacks and the `Job`
model are touched, so your own JobRunr (OSS or Pro) wins at runtime. Built against 7.5.x.

Notes:

- Telemetry never breaks job processing: an unexpected callback sequence is WARN-logged and
  skipped; no job-argument capture (business data / PII stance).
- Suspend job bodies: wrap in `withCanonicalCoroutineContext { ... }` before switching
  dispatchers.
