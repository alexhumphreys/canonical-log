package io.github.alexhumphreys.canonicallog.scheduling

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import java.time.Instant
import java.util.UUID

/**
 * The [WorkUnitAdapter] for `@Scheduled` methods, driven by Spring's own scheduled-task
 * observation. The input is the [ScheduledTaskObservationContext] Spring builds per run, which
 * carries the target class and method — so the job identity is derived mechanically from the
 * method, no per-job configuration needed.
 *
 * Fields written by [enrich]:
 * - `work_unit_id`, `work_unit_kind` (= `scheduled_job`) — identity, mirroring the HTTP adapter.
 * - `job_name` — `<SimpleClassName>.<methodName>`, low-cardinality and bounded (one value per
 *   scheduled method).
 * - `job_duration_ms` — run duration in integer ms.
 * - On a thrown outcome: `error`/`error_class`, and `error_reason=exception` unless the job body
 *   already set one via `markFailed` (deferring to handler intent, exactly like `HttpWorkUnitAdapter`).
 *
 * Per-run values (rows processed, tenant, ...) are the job body's job via `CanonicalLog.put`.
 *
 * Replace it by registering your own `WorkUnitAdapter<ScheduledTaskObservationContext>` bean.
 */
public class ScheduledJobWorkUnitAdapter : WorkUnitAdapter<ScheduledTaskObservationContext> {

    override fun describe(input: ScheduledTaskObservationContext): WorkUnit = WorkUnit(
        id = UUID.randomUUID().toString(),
        kind = WORK_UNIT_KIND,
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: ScheduledTaskObservationContext, outcome: Outcome) {
        ctx.put(CanonicalFields.WORK_UNIT_ID, ctx.workUnit.id)
        ctx.put(CanonicalFields.WORK_UNIT_KIND, ctx.workUnit.kind)
        ctx.put(JOB_NAME, jobName(input))
        ctx.put(JOB_DURATION_MS, outcome.durationMs)

        if (outcome is Outcome.Threw) {
            ctx.put(CanonicalFields.ERROR, true)
            ctx.put(CanonicalFields.ERROR_CLASS, outcome.cause::class.qualifiedName ?: "unknown")
            if (ctx.snapshot()[CanonicalFields.ERROR_REASON] == null) {
                ctx.put(CanonicalFields.ERROR_REASON, "exception")
            }
        }
    }

    private fun jobName(input: ScheduledTaskObservationContext): String {
        val simpleName = input.targetClass?.simpleName ?: "unknown"
        return "$simpleName.${input.method.name}"
    }

    public companion object {
        public const val WORK_UNIT_KIND: String = "scheduled_job"
        public const val JOB_NAME: String = "job_name"
        public const val JOB_DURATION_MS: String = "job_duration_ms"
    }
}
