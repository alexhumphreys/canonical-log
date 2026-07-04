package io.github.alexhumphreys.canonicallog.servlet

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogSampler
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.openCanonicalWorkUnit
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Same logger name core uses for its own failure reporting (throwing emit/enrich),
 * so adopters configure one logger for all canonical-log library warnings.
 */
private val libraryLogger = LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog")

/**
 * The framework-neutral HTTP work-unit lifecycle — the hardest-won code in the repo, shared
 * by every servlet integration (the Spring `CanonicalLogFilter`, [CanonicalLogServletFilter],
 * and any other servlet-based caller). Given a request/response and the collaborators, it:
 *
 *  1. Opens a canonical work unit (binds the threadlocal + MDC, records nesting).
 *  2. Runs [invokeChain] (the filter chain / handler invocation).
 *  3. Splits on sync vs async: if the handler completed synchronously, enriches + emits
 *     inline; if `request.isAsyncStarted` is true, registers a [CanonicalLogAsyncEmitListener]
 *     that defers enrich + emit until the async cycle completes / errors / times out.
 *  4. Unbinds the threadlocal + MDC on *this* thread in `finally`, regardless of when
 *     enrich/emit ultimately run — async emit happens later, on a container thread.
 *
 * Emit-exactly-once: the sync and async paths are mutually exclusive (a throwing chain emits
 * from the catch arm and never registers the listener; a returned-and-async chain hands emit
 * to the listener; otherwise emit is inline), and the listener's own [AtomicBoolean] absorbs
 * the multiple terminal callbacks containers may fire.
 *
 * Telemetry must never fail the request: a throwing [adapter] `enrich` is swallowed and
 * recorded on the line (`canonical_log_enrich_error*`), a throwing [sampler] fails open (the
 * line is still written), a throwing [writer] drops the line — each WARN-logged to the
 * `io.github.alexhumphreys.canonicallog` logger, never propagated to the caller. A thrown
 * handler exception, by contrast, *is* rethrown after the error line is emitted.
 *
 * The caller is responsible for the once-per-dispatch guard (so FORWARD/ERROR dispatches
 * don't open a second unit) and for path exclusion — see [CanonicalLogServletFilter] and the
 * Spring filter.
 */
@DelicateCanonicalLogApi
public fun runCanonicalHttpRequest(
    request: HttpServletRequest,
    response: HttpServletResponse,
    adapter: WorkUnitAdapter<HttpExchange>,
    writer: CanonicalLineWriter,
    sampler: CanonicalLogSampler,
    invokeChain: () -> Unit,
) {
    val exchange = HttpExchange(request, response)
    // Open the blocking work-unit scope (bind threadlocal + MDC, record nesting). We drive
    // the tail ourselves rather than via the withCanonicalLogBlocking closure, because on the
    // async path emit happens later from an AsyncListener — so we must unbind on this thread
    // (finally) independently of when enrich/emit run.
    val scope = openCanonicalWorkUnit(adapter, exchange)
    val ctx = scope.context

    fun emit(error: Throwable?) {
        // Cancellation (client disconnect, async timeout) is not a failure — the scope maps it
        // to Outcome.Cancelled (cancelled=true, no error=true), the same classification core's
        // withCanonicalLog uses, so the servlet and suspend entry points tell the same story.
        val outcome = scope.outcomeFor(error)
        if (error is AsyncTimeoutCancellationException && ctx.snapshot()[CanonicalFields.CANCEL_REASON] == null) {
            ctx.put(CanonicalFields.CANCEL_REASON, "async_timeout")
        }
        scope.enrich(adapter, exchange, outcome)
        val keep = try {
            sampler.shouldEmit(ctx)
        } catch (e: Exception) {
            libraryLogger.warn(
                "sampler threw for work unit {}; failing open, canonical line still written",
                ctx.workUnit.id,
                e,
            )
            true
        }
        if (keep) {
            scope.emit { writer.write(it) }
        }
    }

    try {
        invokeChain()
        if (request.isAsyncStarted) {
            request.asyncContext.addListener(CanonicalLogAsyncEmitListener(::emit))
        } else {
            emit(error = null)
        }
    } catch (t: Throwable) {
        emit(error = t)
        throw t
    } finally {
        scope.unbind()
    }
}

/**
 * Servlet [AsyncListener] that funnels every terminal callback (`onComplete`,
 * `onError`, `onTimeout`) into exactly one `emit` call.
 *
 * Containers vary: some fire `onError` then `onComplete`; some fire `onTimeout` then
 * `onComplete`; some fire `onComplete` alone; pathological cases can fire the same
 * callback twice. The internal [AtomicBoolean] enforces single-emit regardless,
 * so callers don't have to guard their own lambda. The first callback wins —
 * subsequent callbacks are silently dropped (their error/cause is not captured).
 *
 * `onStartAsync` re-registers this listener on the new async cycle (the servlet
 * spec requires manual re-registration after `AsyncContext.dispatch()`); the
 * single-emit guard makes that safe even if a container ends up holding two
 * registrations and firing terminal callbacks twice.
 */
internal class CanonicalLogAsyncEmitListener(
    private val emit: (Throwable?) -> Unit,
) : AsyncListener {
    private val emitted = AtomicBoolean(false)

    override fun onComplete(event: AsyncEvent) = emitOnce(event.throwable)
    override fun onError(event: AsyncEvent) = emitOnce(event.throwable)
    override fun onTimeout(event: AsyncEvent) = emitOnce(AsyncTimeoutCancellationException())
    override fun onStartAsync(event: AsyncEvent) {
        event.asyncContext.addListener(this)
    }

    private fun emitOnce(error: Throwable?) {
        if (emitted.compareAndSet(false, true)) emit(error)
    }
}

/**
 * Cancellation signal synthesized when the servlet container's async timeout fires.
 *
 * An async timeout means the work was cut off, not that it failed — so the lifecycle maps it
 * to [Outcome.Cancelled] (`cancelled=true`, `cancel_reason="async_timeout"`) rather than a
 * `TimeoutException` → `Outcome.Threw` → `error=true`. Note the on-the-wire status the
 * container writes after the listeners run is container-dependent (Tomcat sends a 500 error
 * page if nothing completed the request); the canonical line deliberately reports the
 * cancellation (499 by [HttpWorkUnitAdapter]'s convention), not the container's error page.
 */
internal class AsyncTimeoutCancellationException : CancellationException("async dispatch timeout") {
    // A synthesized signal's stack trace (container timer thread) has no diagnostic
    // value; skip constructing it.
    override fun fillInStackTrace(): Throwable = this
}
