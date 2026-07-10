# `canonical-log-jobrunr` — transparent work units for background jobs

**Status:** todo · **Modules:** new `canonical-log-jobrunr`, `canonical-log-core` (constants)
**Depends on:** 020 (landed — writer type in core). Reads better after 024 (graduated
open/close primitive); if 024 hasn't landed, use the delicate API with `@OptIn` like the
scheduling starter does today.

## Problem

JobRunr is a widely used background-job scheduler (persistent queued/recurring jobs on a
worker pool) and, unlike raw poll loops, it *does* have a first-class instrumentation seam:
`JobServerFilter` callbacks fire around each job execution on the processing server. That
makes transparent instrumentation possible — the structural sibling of the
`canonical-log-scheduling-spring-boot-starter` (`work_unit_kind=scheduled_job` for
in-process `@Scheduled` ticks) but framework-free and covering the queued/retried/persistent
job model. Without it, job-heavy services get canonical lines for their HTTP traffic and
nothing for the work that actually dominates their runtime.

## Design

### 1. New module

- `settings.gradle.kts`: add `"canonical-log-jobrunr"`. Package
  `io.github.alexhumphreys.canonicallog.jobrunr`.
- `build.gradle.kts`: `api(project(":canonical-log-core"))`,
  `compileOnly(libs.jobrunr)` — new catalog entries (`jobrunr = "<latest OSS>"`,
  `org.jobrunr:jobrunr`). **OSS artifact only** — everything used
  (`JobServerFilter`, `Job`, job states) must exist in the open-source edition; verify
  nothing referenced is Pro-only. compileOnly so the adopter's JobRunr (OSS or Pro) wins.
  Tests: `testImplementation(libs.jobrunr)` plus whatever the e2e needs (in-memory storage
  provider ships in the main artifact).

### 2. Version-sensitive API check (do this first)

The `JobServerFilter` callback set differs across JobRunr majors (`onProcessing` /
`onProcessed` historically; newer lines add `onProcessingSucceeded` /
`onProcessingFailed(job, exception)`). Pin the catalog version, read that version's
interface, and map:

- open = the callback fired immediately before the job method runs (`onProcessing`).
- terminal = whichever callback(s) fire after the job method returns/throws on that
  attempt. If success and failure arrive via distinct callbacks, both route to one
  `finish(job, throwable?)`; if only `onProcessed` exists, derive the throwable from the
  job's state transition. Record the mapping in the module KDoc.

### 3. `CanonicalJobServerFilter`

```kotlin
public class CanonicalJobServerFilter @JvmOverloads constructor(
    private val writer: CanonicalLineWriter,
    private val adapter: WorkUnitAdapter<Job> = JobRunrWorkUnitAdapter(),
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
) : JobServerFilter
```

- **Same-thread lifecycle assumption**: JobRunr's job performer invokes the filter
  callbacks around the job method on the worker thread. Open with
  `openCanonicalWorkUnit(adapter, job)` in the opening callback; in the terminal callback
  run `outcomeFor` → `enrich` → `unbind` → sampler (fail-open, HTTP-filter policy) → `emit`.
- **Filter instances are shared across workers**, so the scope cannot live in a field.
  Hold it in a `ThreadLocal<CanonicalWorkUnitScope>` inside the filter — valid *because* of
  the same-thread assumption, which the e2e test exists to prove. Defensive posture when
  the assumption breaks (terminal callback with no scope on the thread, or an unexpected
  second open): WARN once on the `io.github.alexhumphreys.canonicallog` logger and skip —
  telemetry never breaks job processing. Clear the ThreadLocal in a `finally` around the
  terminal path so a throwing enrich can't leak a stale scope to the worker's next job.
- Retries need no special handling: each processing attempt is its own work unit (the 024
  recipe rule), and JobRunr re-invokes the full filter chain per attempt.
- Contributors (JDBC, OkHttp, Kafka producer) work inside jobs for free — the binding is
  the ordinary threadlocal. Suspend job bodies: point at `withCanonicalCoroutineContext`
  (existing gotcha) in the KDoc.
- Registration is one line, document both shapes: plain
  `JobRunr.configure()...withJobFilter(CanonicalJobServerFilter(writer))` and the
  framework-integration property/bean equivalents (pointer only).

### 4. `JobRunrWorkUnitAdapter`

- `describe`: `WorkUnit(id = job.id.toString(), kind = "background_job", startedAt = now)`.
  Kind is deliberately distinct from the scheduling starter's `scheduled_job` — different
  execution model, queryable separately.
- `enrich`: `JOB_NAME` (reuse the existing constant the scheduling starter writes) from
  `job.jobName`; new constants `JOB_ID` (`"job_id"`) and `JOB_ATTEMPT`
  (`"job_attempt"`, Long — 1-based attempt number derived from the job's state history,
  e.g. previous `FAILED` states + 1; verify the cheapest correct derivation against the
  pinned version). Duration + standard outcome mapping (`Threw` → error fields with
  check-before-default `error_reason`; `Cancelled` → `cancelled=true` — a deleted/aborted
  job surfacing as interruption maps here, verify what the version actually delivers).
  `CanonicalFieldsTest` pins for the two new constants.
- No job-argument capture — arguments are the job's business data (PII stance), and
  `job_name` + adopter handler fields cover identification.

### 5. Tests (kotest)

- Unit: drive the filter callbacks directly with constructed `Job` instances — open/terminal
  success, terminal-with-exception, terminal-without-open (WARN + no throw), two sequential
  jobs on one thread (no scope/binding leak between them — the phantom-nesting check),
  sampler fail-open, throwing enrich still unbinds and clears the ThreadLocal.
- **E2e (required — it validates the same-thread assumption, which is the design's
  foundation):** real `BackgroundJobServer` + `InMemoryStorageProvider`, enqueue a job whose
  body calls `CanonicalLog.put(...)`; await processing; assert exactly one line with
  `background_job` kind, `job_name`, `job_id`, handler field, duration. Second case: a job
  that throws once then succeeds on retry → two lines, attempt numbers 1 and 2, first with
  `error=true`. Use a `CanonicalLineWriter` that appends to a synchronized list (no logback
  needed).

### 6. Docs

`docs/CLAUDE.md`: module-layout entry — spell out the ThreadLocal-scope-keyed-by-worker
design and the same-thread assumption with its defensive fallback, plus the
`scheduled_job` vs `background_job` kind split. README: one line in the integrations list.

## Acceptance

- Registering one filter gives one canonical line per job processing attempt — success,
  failure, and retry attempts each with correct `job_attempt` — with handler `put`s and
  contributor fields landing; job processing is never failed or slowed by a throwing
  adapter/sampler/writer.
- E2e on a real background job server passes (same-thread assumption proven, no leaks
  across sequential jobs on a worker).
- JobRunr is compileOnly and OSS-only APIs are used; new constants pinned.
