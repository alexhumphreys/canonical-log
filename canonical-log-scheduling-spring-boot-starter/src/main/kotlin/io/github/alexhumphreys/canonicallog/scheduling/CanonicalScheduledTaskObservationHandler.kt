package io.github.alexhumphreys.canonicallog.scheduling

import io.github.alexhumphreys.canonicallog.CanonicalWorkUnitScope
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.EmitFn
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.openCanonicalWorkUnit
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import org.springframework.scheduling.support.ScheduledTaskObservationContext

/**
 * Bridges Spring's scheduled-task observation to a canonical work unit, so a plain `@Scheduled`
 * method produces a canonical line with no wrapping in the job body.
 *
 * Spring instruments each run as `observation.observe(runnable)`, which drives the handler in a
 * fixed order — `onStart` → `onScopeOpened` → *method runs* → `onScopeClosed` → `onStop` — that
 * maps cleanly onto the blocking [openCanonicalWorkUnit] / [CanonicalWorkUnitScope] lifecycle:
 *
 *  - **`onScopeOpened`** opens the work unit and binds it to the scheduler thread, so contributors
 *    invoked during the method (the JDBC listener, OkHttp interceptor) resolve to it with no extra
 *    wiring. The scope is stashed on the observation context.
 *  - **`onScopeClosed`** unbinds — the method is done, the thread is about to be reused.
 *  - **`onStop`** classifies the outcome from `context.error` (a thrown method sets it via
 *    `observation.error`), runs the adapter's `enrich`, and emits. These don't need the binding,
 *    so running them after unbind is fine — and matches how the servlet filter finalizes after it
 *    has already unbound on the request thread.
 *
 * The scope's own guards mean a throwing `enrich` or `emit` is swallowed-and-recorded rather than
 * failing the scheduled run.
 */
@OptIn(DelicateCanonicalLogApi::class)
public class CanonicalScheduledTaskObservationHandler(
    private val adapter: WorkUnitAdapter<ScheduledTaskObservationContext>,
    private val emit: EmitFn,
) : ObservationHandler<ScheduledTaskObservationContext> {

    override fun supportsContext(context: Observation.Context): Boolean =
        context is ScheduledTaskObservationContext

    override fun onScopeOpened(context: ScheduledTaskObservationContext) {
        context.put(SCOPE_KEY, openCanonicalWorkUnit(adapter, context))
    }

    override fun onScopeClosed(context: ScheduledTaskObservationContext) {
        context.get<CanonicalWorkUnitScope>(SCOPE_KEY)?.unbind()
    }

    override fun onStop(context: ScheduledTaskObservationContext) {
        val scope = context.get<CanonicalWorkUnitScope>(SCOPE_KEY) ?: return
        val outcome = scope.outcomeFor(context.error)
        scope.enrich(adapter, context, outcome)
        scope.emit(emit)
    }

    private companion object {
        // Key for stashing the open scope on the observation context across the open→stop callbacks.
        private val SCOPE_KEY = CanonicalWorkUnitScope::class.java
    }
}
