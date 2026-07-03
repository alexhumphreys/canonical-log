package io.github.alexhumphreys.canonicallog.sample

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import java.time.Instant
import java.util.UUID

/**
 * A hand-written [WorkUnitAdapter] for a non-HTTP entry point — a scheduled job.
 *
 * This is the whole point of the second entry point: `WorkUnitAdapter` is meant to
 * generalize past HTTP, and the cheapest way to prove it is to write one for a job by
 * hand. The input is the job name; [describe] mints the work unit and [enrich] writes the
 * mechanically-uniform fields every job run has — identity, kind, name, duration, and the
 * outcome markers. It intentionally mirrors `HttpWorkUnitAdapter`'s outcome handling
 * (`error`/`error_class`/`error_reason` on a thrown outcome, deferring to a handler-set
 * `error_reason`) so the job's canonical line reads the same way an HTTP line does.
 *
 * Per-run values (how many rows the report covered, which tenant, ...) are the job body's
 * job via `CanonicalLog.put`, exactly as a request handler contributes per-request fields.
 */
class ScheduledJobAdapter : WorkUnitAdapter<String> {

    override fun describe(input: String): WorkUnit = WorkUnit(
        id = UUID.randomUUID().toString(),
        kind = WORK_UNIT_KIND,
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put(CanonicalFields.WORK_UNIT_ID, ctx.workUnit.id)
        ctx.put(CanonicalFields.WORK_UNIT_KIND, ctx.workUnit.kind)
        ctx.put(JOB_NAME, input)
        ctx.put(JOB_DURATION_MS, outcome.durationMs)

        if (outcome is Outcome.Threw) {
            ctx.put(CanonicalFields.ERROR, true)
            ctx.put(CanonicalFields.ERROR_CLASS, outcome.cause::class.qualifiedName ?: "unknown")
            // Defer to a handler-set reason (markFailed), matching HttpWorkUnitAdapter.
            if (ctx.snapshot()[CanonicalFields.ERROR_REASON] == null) {
                ctx.put(CanonicalFields.ERROR_REASON, "exception")
            }
        }
    }

    companion object {
        const val WORK_UNIT_KIND = "scheduled_job"
        const val JOB_NAME = "job_name"
        const val JOB_DURATION_MS = "job_duration_ms"
    }
}
