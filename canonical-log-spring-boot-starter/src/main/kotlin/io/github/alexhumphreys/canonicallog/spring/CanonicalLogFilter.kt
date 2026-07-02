package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.bindCurrentCanonicalContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servlet filter that opens a canonical work unit for each HTTP request and emits
 * exactly one canonical log line when the request completes — including for
 * asynchronous handlers (suspend controllers, `Callable`/`DeferredResult` returns,
 * SSE streams).
 *
 * Lifecycle:
 *  1. On request entry: create [CanonicalLogContext], install it as the
 *     current-thread canonical context (so blocking-thread contributors see it
 *     without any explicit setup).
 *  2. Invoke `chain.doFilter`. If the handler is synchronous, control returns here
 *     after the response is fully rendered; the filter enriches and emits inline.
 *  3. If `chain.doFilter` returned but `request.isAsyncStarted` is `true`, the
 *     handler is still running asynchronously. The filter registers an
 *     [AsyncListener] that fires on completion / error / timeout, defers
 *     enrichment + emit until then.
 *  4. The thread-local binding is unwound on this thread before this filter
 *     returns. Coroutine-aware adopters should use `withCanonicalCoroutineContext`
 *     to lift the context into the coroutine before any dispatcher switch.
 *
 * Single-emit invariant: an [AtomicBoolean] guard ensures at most one canonical
 * line per request, even if both `onError` and `onComplete` fire on the listener.
 *
 * Both collaborators are injectable (the auto-configuration resolves user beans):
 *  - [adapter] describes/enriches the work unit. To add uniform fields (e.g. a tenant
 *    ID from a header), compose rather than subclass — write a
 *    `WorkUnitAdapter<HttpExchange>` that delegates `describe`/`enrich` to
 *    [HttpWorkUnitAdapter] and then puts its extra fields.
 *  - [writer] is the sink for the finalized line; defaults to the logstash
 *    `"canonical"` logger via [LogstashCanonicalLineWriter].
 */
@OptIn(DelicateCanonicalLogApi::class)
public class CanonicalLogFilter(
    private val adapter: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(),
    private val writer: CanonicalLineWriter = LogstashCanonicalLineWriter(),
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val exchange = HttpExchange(request, response)
        val ctx = CanonicalLogContext(adapter.describe(exchange))
        val previous = bindCurrentCanonicalContext(ctx)
        val startNs = System.nanoTime()

        fun emit(error: Throwable?) {
            val outcome = if (error != null) {
                Outcome.Threw(elapsedMs(startNs), error)
            } else {
                Outcome.Completed(elapsedMs(startNs))
            }
            adapter.enrich(ctx, exchange, outcome)
            writer.write(ctx)
        }

        try {
            // Sync and async paths are mutually exclusive: if chain.doFilter throws,
            // we never register the listener (and emit fires from the catch arm); if
            // the chain returns and async was started, the listener owns emit; otherwise
            // we emit synchronously here. So emit() is called exactly once per request
            // — single-shot semantics live in [CanonicalLogAsyncEmitListener] for the
            // async case, where servlet containers may fire multiple terminal callbacks.
            filterChain.doFilter(request, response)
            if (request.isAsyncStarted) {
                request.asyncContext.addListener(CanonicalLogAsyncEmitListener(::emit))
            } else {
                emit(error = null)
            }
        } catch (t: Throwable) {
            emit(error = t)
            throw t
        } finally {
            bindCurrentCanonicalContext(previous)
        }
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000
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
 *
 * See `CanonicalLogFilterAsyncPropertyTest` for the orderings property pin.
 */
internal class CanonicalLogAsyncEmitListener(
    private val emit: (Throwable?) -> Unit,
) : AsyncListener {
    private val emitted = AtomicBoolean(false)

    override fun onComplete(event: AsyncEvent) = emitOnce(event.throwable)
    override fun onError(event: AsyncEvent) = emitOnce(event.throwable)
    override fun onTimeout(event: AsyncEvent) = emitOnce(TimeoutException("async dispatch timeout"))
    override fun onStartAsync(event: AsyncEvent) {
        event.asyncContext.addListener(this)
    }

    private fun emitOnce(error: Throwable?) {
        if (emitted.compareAndSet(false, true)) emit(error)
    }
}
