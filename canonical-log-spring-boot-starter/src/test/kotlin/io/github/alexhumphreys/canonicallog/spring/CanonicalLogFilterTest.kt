package io.github.alexhumphreys.canonicallog.spring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import jakarta.servlet.FilterChain
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Read the canonical-line fields back from the log event for assertions.
 *
 * Reflection is used because logstash-logback-encoder's [MapEntriesAppendingMarker]
 * has no public accessor for its underlying map (only `writeTo(JsonGenerator)`,
 * which would require round-tripping through Jackson and lose Kotlin-side type
 * fidelity, e.g. `Long` vs `Int`). If the encoder ever exposes a public
 * `getFieldMap()`, swap to that.
 *
 * Fails loudly if no canonical line was emitted — that's almost always a real test
 * failure (the filter didn't run, or didn't reach the emit), and a null return
 * would mask it as "field not present."
 */
private fun lastCanonicalSnapshot(appender: ListAppender<ILoggingEvent>): Map<String, Any> {
    val event = appender.list.lastOrNull { it.loggerName == "canonical" }
        ?: error("no canonical log event captured")
    val args: Array<out Any?> = event.argumentArray ?: emptyArray()
    val markers: List<Any> = (event.markerList ?: emptyList<Marker>()) + args.filterNotNull()
    return markers.filterIsInstance<MapEntriesAppendingMarker>()
        .map { marker ->
            val mapField = generateSequence<Class<*>>(marker::class.java) { it.superclass }
                .firstNotNullOfOrNull { cls ->
                    cls.declaredFields.firstOrNull { f ->
                        Map::class.java.isAssignableFrom(f.type)
                    }
                } ?: error("no map field on ${marker::class.java}")
            mapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            mapField.get(marker) as Map<String, Any>
        }
        .fold(emptyMap<String, Any>()) { acc, m -> acc + m }
}

private fun attachAppender(): ListAppender<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val logger = LoggerFactory.getLogger("canonical") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.INFO
    return appender
}

private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
    (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
}

/** Capture the swallow-and-warn reporting for throwing enrich/sampler/writer. */
private fun attachLibraryWarnAppender(): ListAppender<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val logger = LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.WARN
    return appender
}

private fun detachLibraryWarnAppender(appender: ListAppender<ILoggingEvent>) {
    (LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog") as LogbackLogger)
        .detachAppender(appender)
}

class CanonicalLogFilterTest : DescribeSpec({

    describe("CanonicalLogFilter") {

        it("emits exactly one canonical line for a synchronous handler") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/sync")
                val res = MockHttpServletResponse().apply { status = 200 }
                CanonicalLogFilter().doFilter(req, res) { _, _ -> }
                appender.list.count { it.loggerName == "canonical" } shouldBe 1
                val snap = lastCanonicalSnapshot(appender)
                snap["url_path"] shouldBe "/sync"
                snap["http_response_status_code"] shouldBe 200
            } finally {
                detachAppender(appender)
            }
        }

        it("emits a human-readable message summarizing the request") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/sync")
                val res = MockHttpServletResponse().apply { status = 200 }
                CanonicalLogFilter().doFilter(req, res) { _, _ -> }
                val event = appender.list.last { it.loggerName == "canonical" }
                // No route matched (MockHttpServletRequest sets no best-matching-pattern
                // attribute), so the message falls back to url_path.
                event.formattedMessage shouldMatch Regex("""GET /sync 200 \d+ms""")
            } finally {
                detachAppender(appender)
            }
        }

        it("defers emit until AsyncContext completes for an async handler") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/async").apply { isAsyncSupported = true }
                val res = MockHttpServletResponse().apply { status = 200 }

                val chain = FilterChain { _, _ -> req.startAsync(req, res) }

                CanonicalLogFilter().doFilter(req, res, chain)

                // Filter returned, but the handler is still "running" async — no emit yet.
                appender.list.count { it.loggerName == "canonical" } shouldBe 0

                // Simulate the async handler finishing.
                (req.asyncContext as MockAsyncContext).complete()

                appender.list.count { it.loggerName == "canonical" } shouldBe 1
                val snap = lastCanonicalSnapshot(appender)
                snap["url_path"] shouldBe "/async"
                snap["http_response_status_code"] shouldBe 200
            } finally {
                detachAppender(appender)
            }
        }

        it("clears the threadlocal after a synchronous request") {
            val req = MockHttpServletRequest("GET", "/sync")
            val res = MockHttpServletResponse().apply { status = 200 }
            CanonicalLogFilter().doFilter(req, res) { _, _ -> }
            io.github.alexhumphreys.canonicallog.currentCanonicalContext() shouldBe null
        }

        it("clears the threadlocal after an async request even though emit is deferred") {
            val req = MockHttpServletRequest("GET", "/async").apply { isAsyncSupported = true }
            val res = MockHttpServletResponse().apply { status = 200 }
            val chain = jakarta.servlet.FilterChain { _, _ -> req.startAsync(req, res) }
            CanonicalLogFilter().doFilter(req, res, chain)
            // Filter has returned; servlet thread must be empty even though the work unit
            // is still alive and waiting for AsyncListener.onComplete.
            io.github.alexhumphreys.canonicallog.currentCanonicalContext() shouldBe null
        }

        it("binds work_unit_id in MDC during the request so ordinary log lines correlate, and clears it after") {
            val appLogAppender = ListAppender<ILoggingEvent>().also { it.start() }
            val appLogger = LoggerFactory.getLogger("sample-app") as LogbackLogger
            appLogger.addAppender(appLogAppender)
            appLogger.level = Level.INFO
            try {
                val req = MockHttpServletRequest("GET", "/sync")
                val res = MockHttpServletResponse().apply { status = 200 }
                var workUnitId: String? = null
                CanonicalLogFilter().doFilter(req, res) { _, _ ->
                    workUnitId = io.github.alexhumphreys.canonicallog
                        .currentCanonicalContext()?.workUnit?.id
                    appLogger.info("an ordinary line during the request")
                }
                // Logback stamps each event with the MDC at log time — the ordinary
                // line carries the same id the canonical line will report.
                (workUnitId == null) shouldBe false
                appLogAppender.list.single().mdcPropertyMap["work_unit_id"] shouldBe workUnitId
                org.slf4j.MDC.get("work_unit_id") shouldBe null
            } finally {
                appLogger.detachAppender(appLogAppender)
            }
        }

        it("clears MDC on the servlet thread after an async request even though emit is deferred") {
            val req = MockHttpServletRequest("GET", "/async").apply { isAsyncSupported = true }
            val res = MockHttpServletResponse().apply { status = 200 }
            var mdcDuringChain: String? = null
            val chain = FilterChain { _, _ ->
                mdcDuringChain = org.slf4j.MDC.get("work_unit_id")
                req.startAsync(req, res)
            }
            CanonicalLogFilter().doFilter(req, res, chain)
            (mdcDuringChain == null) shouldBe false
            org.slf4j.MDC.get("work_unit_id") shouldBe null
        }

        it("clears MDC even when the handler throws") {
            val req = MockHttpServletRequest("GET", "/boom")
            val res = MockHttpServletResponse().apply { status = 500 }
            runCatching {
                CanonicalLogFilter().doFilter(req, res) { _, _ -> error("kaboom") }
            }
            org.slf4j.MDC.get("work_unit_id") shouldBe null
        }

        it("clears the threadlocal even when the handler throws") {
            val req = MockHttpServletRequest("GET", "/boom")
            val res = MockHttpServletResponse().apply { status = 500 }
            runCatching {
                CanonicalLogFilter().doFilter(req, res) { _, _ -> error("kaboom") }
            }
            io.github.alexhumphreys.canonicallog.currentCanonicalContext() shouldBe null
        }

        it("skips excluded paths entirely: chain runs, no context, no line") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/actuator/health")
                val res = MockHttpServletResponse().apply { status = 200 }
                val filter = CanonicalLogFilter(excludePaths = listOf("/actuator/**"))
                var chainInvoked = false
                var contextDuringChain: Any? = Any()
                filter.doFilter(req, res) { _, _ ->
                    chainInvoked = true
                    contextDuringChain = io.github.alexhumphreys.canonicallog.currentCanonicalContext()
                }
                chainInvoked shouldBe true
                contextDuringChain shouldBe null
                appender.list.count { it.loggerName == "canonical" } shouldBe 0
            } finally {
                detachAppender(appender)
            }
        }

        it("still logs non-excluded paths when excludePaths is set") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/posts/1")
                val res = MockHttpServletResponse().apply { status = 200 }
                val filter = CanonicalLogFilter(excludePaths = listOf("/actuator/**"))
                filter.doFilter(req, res) { _, _ -> }
                appender.list.count { it.loggerName == "canonical" } shouldBe 1
            } finally {
                detachAppender(appender)
            }
        }

        it("sampler sees the enriched line: drops a healthy 200, keeps a thrown failure") {
            val appender = attachAppender()
            try {
                val errorsOnly = CanonicalLogSampler { ctx -> ctx.snapshot()["error"] == true }

                val okReq = MockHttpServletRequest("GET", "/ok")
                val okRes = MockHttpServletResponse().apply { status = 200 }
                CanonicalLogFilter(sampler = errorsOnly).doFilter(okReq, okRes) { _, _ -> }
                appender.list.count { it.loggerName == "canonical" } shouldBe 0

                val boomReq = MockHttpServletRequest("GET", "/boom")
                val boomRes = MockHttpServletResponse().apply { status = 500 }
                runCatching {
                    CanonicalLogFilter(sampler = errorsOnly).doFilter(boomReq, boomRes) { _, _ ->
                        error("kaboom")
                    }
                }
                appender.list.count { it.loggerName == "canonical" } shouldBe 1
                lastCanonicalSnapshot(appender)["error"] shouldBe true
            } finally {
                detachAppender(appender)
            }
        }

        it("a throwing writer is swallowed: request completes, WARN logged, line dropped") {
            val canonical = attachAppender()
            val warns = attachLibraryWarnAppender()
            try {
                val req = MockHttpServletRequest("GET", "/ok")
                val res = MockHttpServletResponse().apply { status = 200 }
                val filter = CanonicalLogFilter(writer = { error("sink blew up") })

                // Must not throw — the writer failure never reaches the caller.
                filter.doFilter(req, res) { _, _ -> }

                canonical.list.count { it.loggerName == "canonical" } shouldBe 0
                warns.list.count { it.level == Level.WARN } shouldBe 1
                io.github.alexhumphreys.canonicallog.currentCanonicalContext() shouldBe null
            } finally {
                detachAppender(canonical)
                detachLibraryWarnAppender(warns)
            }
        }

        it("a throwing sampler fails open: line still written, WARN logged, request completes") {
            val canonical = attachAppender()
            val warns = attachLibraryWarnAppender()
            try {
                val req = MockHttpServletRequest("GET", "/ok")
                val res = MockHttpServletResponse().apply { status = 200 }
                val filter = CanonicalLogFilter(sampler = { error("sampler blew up") })

                filter.doFilter(req, res) { _, _ -> }

                canonical.list.count { it.loggerName == "canonical" } shouldBe 1
                warns.list.count { it.level == Level.WARN } shouldBe 1
            } finally {
                detachAppender(canonical)
                detachLibraryWarnAppender(warns)
            }
        }

        it("a throwing adapter.enrich is swallowed: line still written with the enrich-error markers, WARN logged") {
            val canonical = attachAppender()
            val warns = attachLibraryWarnAppender()
            try {
                val req = MockHttpServletRequest("GET", "/ok")
                val res = MockHttpServletResponse().apply { status = 200 }
                val throwingAdapter = object : io.github.alexhumphreys.canonicallog.WorkUnitAdapter<HttpExchange> {
                    private val delegate = HttpWorkUnitAdapter()
                    override fun describe(input: HttpExchange) = delegate.describe(input)
                    override fun enrich(
                        ctx: io.github.alexhumphreys.canonicallog.CanonicalLogContext,
                        input: HttpExchange,
                        outcome: io.github.alexhumphreys.canonicallog.Outcome,
                    ): Unit = error("enrich blew up")
                }

                CanonicalLogFilter(adapter = throwingAdapter).doFilter(req, res) { _, _ -> }

                canonical.list.count { it.loggerName == "canonical" } shouldBe 1
                val snap = lastCanonicalSnapshot(canonical)
                snap["canonical_log_enrich_error"] shouldBe true
                snap["canonical_log_enrich_error_class"] shouldBe "java.lang.IllegalStateException"
                warns.list.count { it.level == Level.WARN } shouldBe 1
            } finally {
                detachAppender(canonical)
                detachLibraryWarnAppender(warns)
            }
        }

        it("an async timeout emits a cancelled line, not an error line") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/slow").apply { isAsyncSupported = true }
                val res = MockHttpServletResponse().apply { status = 200 }
                val chain = FilterChain { _, _ -> req.startAsync(req, res) }

                CanonicalLogFilter().doFilter(req, res, chain)

                // Simulate the container's async timeout firing, then the onComplete
                // that containers deliver afterwards — single-emit guard absorbs it.
                val asyncCtx = req.asyncContext as MockAsyncContext
                val event = jakarta.servlet.AsyncEvent(asyncCtx, req, res)
                asyncCtx.listeners.forEach { it.onTimeout(event) }
                asyncCtx.listeners.forEach { it.onComplete(event) }

                appender.list.count { it.loggerName == "canonical" } shouldBe 1
                val snap = lastCanonicalSnapshot(appender)
                snap["cancelled"] shouldBe true
                snap["cancel_reason"] shouldBe "async_timeout"
                snap["http_response_status_code"] shouldBe 499
                snap.containsKey("error") shouldBe false
                snap.containsKey("error_class") shouldBe false
            } finally {
                detachAppender(appender)
            }
        }

        it("emits exactly once when a synchronous handler throws") {
            val appender = attachAppender()
            try {
                val req = MockHttpServletRequest("GET", "/boom")
                val res = MockHttpServletResponse().apply { status = 500 }
                val ex = runCatching {
                    CanonicalLogFilter().doFilter(req, res) { _, _ -> error("kaboom") }
                }.exceptionOrNull()
                (ex is IllegalStateException) shouldBe true
                appender.list.count { it.loggerName == "canonical" } shouldBe 1
                val snap = lastCanonicalSnapshot(appender)
                snap["error"] shouldBe true
                snap["error_class"] shouldBe "java.lang.IllegalStateException"
            } finally {
                detachAppender(appender)
            }
        }
    }
})
