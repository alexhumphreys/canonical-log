package io.github.alexhumphreys.canonicallog.jobrunr

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.CanonicalWorkUnitScope
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.openCanonicalWorkUnit
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.filters.JobServerFilter
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * A JobRunr [JobServerFilter] that emits one canonical log line per background-job **processing
 * attempt**, transparently — the adopter registers it once and every job on the server produces a
 * line with no change to any job body.
 *
 * ## Callback mapping (JobRunr 7.5.x)
 * JobRunr invokes the [JobServerFilter] callbacks around the job method **on the worker thread**:
 *  - **[onProcessing]** fires immediately before the job method runs → this is the **open**.
 *  - **[onProcessingSucceeded]** fires after the method returns normally → **terminal**, no throwable.
 *  - **[onProcessingFailed]** fires after the method throws (before JobRunr records the FAILED
 *    state / decides on a retry) → **terminal**, with the throwable → both route to [finish].
 *  - `onFailedAfterRetries` is deliberately **not** handled: it fires on the state-election thread
 *    once retries are exhausted, which is a *storage* event, not a per-attempt processing terminal.
 *
 * Because success and failure arrive on distinct callbacks, [finish] takes a nullable throwable and
 * runs the one shared tail: `outcomeFor` → `enrich` → `unbind` → sampler (fail-open) → `emit`, the
 * same policy the HTTP servlet filter uses.
 *
 * ## Same-thread lifecycle assumption
 * The open and its terminal callback run on the *same* worker thread. A single filter instance is
 * shared across all workers, so the open [CanonicalWorkUnitScope] **cannot** live in a field — it is
 * held in a [ThreadLocal], keyed implicitly by the worker thread. The e2e proves this assumption
 * against a real `BackgroundJobServer`.
 *
 * If the assumption ever breaks — a terminal callback with no scope on the thread, or a second
 * [onProcessing] before the first finished — the filter **warns once** on the
 * `io.github.alexhumphreys.canonicallog` logger and skips: telemetry never breaks job processing.
 * The [ThreadLocal] is cleared in a `finally` around the terminal path so a throwing enrich (or an
 * Error) can't leak a stale scope onto the worker's next job.
 *
 * ## Notes
 *  - **Retries need no special handling**: each attempt is its own work unit; JobRunr re-invokes the
 *    whole filter chain per attempt, and `job_attempt` (see [JobRunrWorkUnitAdapter]) distinguishes them.
 *  - **Contributors work for free**: the JDBC listener, OkHttp interceptor, etc. bind to the open
 *    work unit via the ordinary threadlocal, since the job runs on the bound worker thread.
 *  - **Suspend job bodies**: wrap the suspending work in `withCanonicalCoroutineContext { ... }` so
 *    the binding follows dispatcher switches (the blocking open only sets the threadlocal).
 *
 * ## Registration
 * Plain JobRunr:
 * ```
 * JobRunr.configure()
 *     .useStorageProvider(...)
 *     .withJobFilter(CanonicalJobServerFilter(writer))
 *     .useBackgroundJobServer()
 *     .initialize()
 * ```
 * Spring Boot integration: register a `CanonicalJobServerFilter` bean (or add it to the
 * `BackgroundJobServerConfiguration`'s job filters) — the JobRunr Spring starter picks up
 * `JobServerFilter` beans.
 */
public class CanonicalJobServerFilter @JvmOverloads constructor(
    private val writer: CanonicalLineWriter,
    private val adapter: WorkUnitAdapter<Job> = JobRunrWorkUnitAdapter(),
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
) : JobServerFilter {

    // The open scope for the job currently processing on THIS worker thread. A field would be wrong:
    // one filter instance is shared across all workers. Valid because open and terminal run on the
    // same thread (the same-thread assumption).
    private val activeScope = ThreadLocal<CanonicalWorkUnitScope?>()

    // Guards the "assumption broke" WARN so a persistent misconfiguration doesn't flood the log.
    private val warnedOnce = AtomicBoolean(false)

    override fun onProcessing(job: Job) {
        if (activeScope.get() != null) {
            warnOnce(
                "onProcessing fired for job {} while a canonical work unit is already open on this " +
                    "worker thread — the same-thread assumption was violated; skipping to avoid a leaked scope",
                job.id,
            )
            return
        }
        activeScope.set(openCanonicalWorkUnit(adapter, job))
    }

    override fun onProcessingSucceeded(job: Job) {
        finish(job, throwable = null)
    }

    override fun onProcessingFailed(job: Job, e: Exception) {
        finish(job, throwable = e)
    }

    private fun finish(job: Job, throwable: Throwable?) {
        val scope = activeScope.get()
        if (scope == null) {
            warnOnce(
                "terminal callback fired for job {} with no canonical work unit open on this worker " +
                    "thread — the same-thread assumption was violated; skipping",
                job.id,
            )
            return
        }
        try {
            val outcome = scope.outcomeFor(normalizeTerminal(throwable))
            try {
                scope.enrich(adapter, job, outcome)
            } finally {
                // Unbind on the worker thread before the next job. enrich swallows Exceptions
                // internally, but unbind-in-finally keeps the threadlocal clean even if an Error escapes.
                scope.unbind()
            }
            if (shouldEmit(scope.context)) {
                scope.emit(writer::write)
            }
        } finally {
            // Always drop the scope for this thread — a stale scope must never reach the next job.
            activeScope.remove()
        }
    }

    private fun shouldEmit(context: CanonicalLogContext): Boolean =
        try {
            sampler.shouldEmit(context)
        } catch (e: Exception) {
            libraryLogger.warn(
                "canonical-log sampler threw for job work unit {}; failing open (line still written)",
                context.workUnit.id,
                e,
            )
            true
        }

    /**
     * Normalize a terminal throwable for outcome classification:
     *  - unwrap [InvocationTargetException] (JobRunr invokes job methods reflectively, so a job's own
     *    exception arrives wrapped) so `error_class` is the real exception, not the reflection wrapper;
     *  - map an [InterruptedException] (a deleted/aborted job surfaces as an interruption) to a
     *    [CancellationException] so it classifies as `Outcome.Cancelled` — a cancelled job must not
     *    pollute error rates.
     */
    private fun normalizeTerminal(throwable: Throwable?): Throwable? {
        if (throwable == null) return null
        val unwrapped = if (throwable is InvocationTargetException) throwable.cause ?: throwable else throwable
        return if (unwrapped is InterruptedException) {
            CancellationException("job interrupted (deleted/aborted)").apply { initCause(unwrapped) }
        } else {
            unwrapped
        }
    }

    private fun warnOnce(message: String, arg: Any?) {
        if (warnedOnce.compareAndSet(false, true)) {
            libraryLogger.warn(message, arg)
        }
    }

    private companion object {
        // Package-named logger, matching the rest of the library's own diagnostics.
        private val libraryLogger = LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog")
    }
}
