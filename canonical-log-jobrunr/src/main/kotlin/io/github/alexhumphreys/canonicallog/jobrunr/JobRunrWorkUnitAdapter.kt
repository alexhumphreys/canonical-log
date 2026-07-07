package io.github.alexhumphreys.canonicallog.jobrunr

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.states.FailedState
import java.time.Instant

/**
 * [WorkUnitAdapter] for JobRunr background jobs, capturing the mechanically-uniform fields for
 * every job processed by a `BackgroundJobServer`. Paired with [CanonicalJobServerFilter], it turns
 * each processing attempt into a canonical line with no wrapping in the job body.
 *
 * `work_unit_kind` is `background_job`, deliberately distinct from the scheduling starter's
 * `scheduled_job`: both are "jobs", but a JobRunr background job (durable, retried, dashboard-backed)
 * and a Spring `@Scheduled` tick are different enough that a query should be able to tell them apart.
 *
 * Fields written by [enrich]:
 *  - `work_unit_id` / `work_unit_kind` — identity, mirroring the other adapters.
 *  - [CanonicalFields.JOB_ID] — JobRunr's own `Job.id`, so the line joins back to the dashboard/storage.
 *  - [CanonicalFields.JOB_NAME] — `Job.jobName` (its `@Job(name=...)` or the derived class.method name).
 *  - [CanonicalFields.JOB_ATTEMPT] — the 1-based attempt number for *this* run (see [attemptNumber]).
 *  - `job_duration_ms` — run duration in integer ms (same key the scheduling starter uses).
 *  - On a thrown outcome: `error` / `error_class`, plus `error_reason=exception` unless the job body
 *    already set one via `markFailed` (check-before-default, exactly like `HttpWorkUnitAdapter`).
 *  - On a cancelled outcome (a deleted/aborted job surfacing as an interruption — see
 *    [CanonicalJobServerFilter]): `cancelled=true` plus a default `cancel_reason`, never `error`.
 *
 * Per-run values (rows processed, tenant, business result) are the job body's job via `CanonicalLog.put`.
 * **Job arguments are never captured** — they are business data (PII stance), the same discipline the
 * SQS adapter follows for message bodies.
 *
 * Replace it by passing your own `WorkUnitAdapter<Job>` to [CanonicalJobServerFilter].
 */
public class JobRunrWorkUnitAdapter : WorkUnitAdapter<Job> {

    override fun describe(input: Job): WorkUnit = WorkUnit(
        id = input.id.toString(),
        kind = WORK_UNIT_KIND,
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: Job, outcome: Outcome) {
        ctx.put(CanonicalFields.WORK_UNIT_ID, ctx.workUnit.id)
        ctx.put(CanonicalFields.WORK_UNIT_KIND, ctx.workUnit.kind)
        ctx.put(CanonicalFields.JOB_ID, input.id.toString())
        ctx.put(CanonicalFields.JOB_NAME, input.jobName)
        ctx.put(CanonicalFields.JOB_ATTEMPT, attemptNumber(input))
        ctx.put(JOB_DURATION_MS, outcome.durationMs)

        when (outcome) {
            is Outcome.Threw -> {
                ctx.put(CanonicalFields.ERROR, true)
                ctx.put(CanonicalFields.ERROR_CLASS, outcome.cause::class.qualifiedName ?: "unknown")
                if (ctx.snapshot()[CanonicalFields.ERROR_REASON] == null) {
                    ctx.put(CanonicalFields.ERROR_REASON, "exception")
                }
            }
            is Outcome.Cancelled -> {
                ctx.put(CanonicalFields.CANCELLED, true)
                if (ctx.snapshot()[CanonicalFields.CANCEL_REASON] == null) {
                    ctx.put(CanonicalFields.CANCEL_REASON, "cancelled")
                }
            }
            is Outcome.Completed -> Unit
        }
    }

    /**
     * The 1-based attempt number: the count of prior `FAILED` states in the job's history plus one.
     *
     * This is correct at *any* of the filter callbacks because JobRunr fires them while the job is
     * still in `PROCESSING` — the current attempt's `succeeded()` / `failed()` transition is applied
     * only *after* the filter chain returns (see `BackgroundJobPerformer`). So on the first run there
     * are zero failed states (→ 1), and on the run after one failure there is exactly one (→ 2).
     */
    private fun attemptNumber(job: Job): Long =
        job.jobStates.count { it is FailedState }.toLong() + 1

    public companion object {
        /** `work_unit_kind` value — deliberately distinct from the scheduling starter's `scheduled_job`. */
        public const val WORK_UNIT_KIND: String = "background_job"

        /** `Long` — run duration in integer ms; the same key the scheduling starter's adapter writes. */
        public const val JOB_DURATION_MS: String = "job_duration_ms"
    }
}
