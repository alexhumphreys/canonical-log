package io.canonlog.spring

import io.canonlog.CanonicalLogContext
import io.canonlog.DelicateCanonicalLogApi
import io.canonlog.Outcome
import io.canonlog.bindCurrentCanonicalContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
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
 */
@OptIn(DelicateCanonicalLogApi::class)
public class CanonicalLogFilter : OncePerRequestFilter() {
    private val canonicalLogger = LoggerFactory.getLogger("canonical")
    private val adapter = HttpWorkUnitAdapter()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val exchange = HttpExchange(request, response)
        val ctx = CanonicalLogContext(adapter.describe(exchange))
        val previous = bindCurrentCanonicalContext(ctx)
        val startNs = System.nanoTime()
        val emitted = AtomicBoolean(false)

        fun emit(error: Throwable?) {
            if (!emitted.compareAndSet(false, true)) return
            val outcome = if (error != null) {
                Outcome.Threw(elapsedMs(startNs), error)
            } else {
                Outcome.Completed(elapsedMs(startNs))
            }
            adapter.enrich(ctx, exchange, outcome)
            canonicalLogger.info("canonical", StructuredArguments.entries(ctx.snapshot()))
        }

        try {
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
 * `onError`, `onTimeout`) into a single `emit` call. The single-emit invariant is
 * enforced by the caller's emit lambda (typically guarded by an [java.util.concurrent.atomic.AtomicBoolean]).
 *
 * Containers can fire `onError` and `onComplete` in either order, fire `onTimeout`
 * before `onComplete`, or fire `onComplete` alone — the emit lambda must be safe
 * under all such orderings. See `CanonicalLogFilterAsyncPropertyTest` for the
 * property pin.
 */
internal class CanonicalLogAsyncEmitListener(
    private val emit: (Throwable?) -> Unit,
) : AsyncListener {
    override fun onComplete(event: AsyncEvent) = emit(event.throwable)
    override fun onError(event: AsyncEvent) = emit(event.throwable)
    override fun onTimeout(event: AsyncEvent) = emit(TimeoutException("async dispatch timeout"))
    override fun onStartAsync(event: AsyncEvent) {
        // If the handler dispatches again, propagate the listener.
        event.asyncContext.addListener(this)
    }
}
