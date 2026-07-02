package io.github.alexhumphreys.canonicallog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        ctx.put("outcome", outcome::class.simpleName)
    }
}

private fun nestedHelper(value: String) {
    CanonicalLog.put("nested_field", value)
}

private class ThrowingEnrichAdapter(private val toThrow: Throwable) : WorkUnitAdapter<String> {
    var enrichCalls: Int = 0
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
        enrichCalls++
        throw toThrow
    }
}

/**
 * Capture the library's own WARN reporting (swallowed emit/enrich failures) on the
 * `io.github.alexhumphreys.canonicallog` logger — same ListAppender pattern as the
 * starter's `CanonicalLogFilterTest`.
 */
private fun attachLibraryWarnAppender(): ListAppender<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val logger = LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.WARN
    return appender
}

private fun detachLibraryWarnAppender(appender: ListAppender<ILoggingEvent>) {
    (LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog") as LogbackLogger).detachAppender(appender)
}

@OptIn(DelicateCanonicalLogApi::class)
class WithCanonicalLogTest : DescribeSpec({

    describe("withCanonicalLogBlocking") {
        it("calls emit exactly once on success and returns block result") {
            val emitCount = AtomicInteger()
            var captured: CanonicalLogContext? = null

            val result = withCanonicalLogBlocking(
                adapter = nullAdapter,
                input = "wu-1",
                emit = { ctx -> emitCount.incrementAndGet(); captured = ctx },
            ) { ctx ->
                ctx.put("inside", "yes")
                "ok"
            }

            result shouldBe "ok"
            emitCount.get() shouldBe 1
            captured?.snapshot()?.get("inside") shouldBe "yes"
            captured?.snapshot()?.get("outcome") shouldBe "Completed"
        }

        it("calls emit exactly once on exception and rethrows") {
            val emitCount = AtomicInteger()
            var captured: CanonicalLogContext? = null

            val ex = runCatching {
                withCanonicalLogBlocking(
                    adapter = nullAdapter,
                    input = "wu-1",
                    emit = { ctx -> emitCount.incrementAndGet(); captured = ctx },
                ) {
                    error("boom")
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<IllegalStateException>()
            ex.message shouldBe "boom"
            emitCount.get() shouldBe 1
            captured?.snapshot()?.get("outcome") shouldBe "Threw"
        }

        it("saves and restores prior thread-local") {
            val outerCtx = CanonicalLogContext(WorkUnit("outer", "test", Instant.now()))
            threadLocalContext.set(outerCtx)
            try {
                CanonicalLog.put("from_outer", "yes")

                withCanonicalLogBlocking(
                    adapter = nullAdapter,
                    input = "inner",
                    emit = { },
                ) {
                    CanonicalLog.put("from_inner", "yes")
                    threadLocalContext.get() shouldNotBe null
                    threadLocalContext.get()?.workUnit?.id shouldBe "inner"
                }

                threadLocalContext.get() shouldBe outerCtx
                outerCtx.snapshot()["from_outer"] shouldBe "yes"
                outerCtx.snapshot().containsKey("from_inner") shouldBe false
            } finally {
                threadLocalContext.set(null)
            }
        }

        it("CanonicalLog.put from nested function lands in the right context") {
            var snap: Map<String, Any> = emptyMap()
            withCanonicalLogBlocking(
                adapter = nullAdapter,
                input = "wu",
                emit = { ctx -> snap = ctx.snapshot() },
            ) {
                nestedHelper("layer1")
            }
            snap["nested_field"] shouldBe "layer1"
        }

        it("calls adapter.enrich exactly once even on success path") {
            val adapter = object : WorkUnitAdapter<String> {
                var calls = 0
                override fun describe(input: String) = WorkUnit(input, "test", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    calls++
                }
            }
            withCanonicalLogBlocking(adapter, "wu", { }) { "ok" }
            adapter.calls shouldBe 1
        }

        it("if adapter.enrich throws on success, the block result is returned, the line is marked, and a WARN is logged") {
            val appender = attachLibraryWarnAppender()
            try {
                var snap: Map<String, Any> = emptyMap()
                val adapter = ThrowingEnrichAdapter(IllegalStateException("enrich blew up"))

                val result = withCanonicalLogBlocking(adapter, "wu", { snap = it.snapshot() }) { "ok" }

                result shouldBe "ok"
                adapter.enrichCalls shouldBe 1
                snap["canonical_log_enrich_error"] shouldBe true
                snap["canonical_log_enrich_error_class"] shouldBe "java.lang.IllegalStateException"
                currentCanonicalContext() shouldBe null
                val warn = appender.list.single { it.level == Level.WARN }
                warn.formattedMessage shouldContain "wu"
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("a throwing emit (blocking variant) is swallowed: block result returned, WARN logged, threadlocal restored") {
            val appender = attachLibraryWarnAppender()
            try {
                val result = withCanonicalLogBlocking<String, String>(
                    nullAdapter,
                    "wu",
                    { throw IllegalStateException("emit blew up") },
                ) { "ok" }

                result shouldBe "ok"
                // Threadlocal is restored before emit runs, so even a throwing emit leaves
                // it clean — pinning this so a future refactor that moves the restore
                // doesn't silently regress.
                threadLocalContext.get() shouldBe null
                val warn = appender.list.single { it.level == Level.WARN }
                warn.formattedMessage shouldContain "wu"
                warn.throwableProxy?.message shouldBe "emit blew up"
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("a throwing emit never replaces the block's exception: the block's exception is rethrown") {
            val appender = attachLibraryWarnAppender()
            try {
                val blockEx = IllegalArgumentException("block blew up")
                val ex = runCatching {
                    withCanonicalLogBlocking<String, String>(
                        nullAdapter,
                        "wu",
                        { throw IllegalStateException("emit blew up") },
                    ) { throw blockEx }
                }.exceptionOrNull()

                ex shouldBe blockEx
                threadLocalContext.get() shouldBe null
                appender.list.count { it.level == Level.WARN } shouldBe 1
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("Error from block propagates without being captured: no enrich, no emit, threadlocal restored") {
            val adapter = object : WorkUnitAdapter<String> {
                var enrichCalls = 0
                override fun describe(input: String) = WorkUnit(input, "test", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    enrichCalls++
                }
            }
            val emitCount = AtomicInteger()

            val ex = runCatching {
                withCanonicalLogBlocking<String, Unit>(adapter, "wu", { emitCount.incrementAndGet() }) {
                    throw OutOfMemoryError("simulated")
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<OutOfMemoryError>()
            adapter.enrichCalls shouldBe 0
            emitCount.get() shouldBe 0
            threadLocalContext.get() shouldBe null
        }

        it("if both block and adapter.enrich throw, block exception is primary; enrich captured on the canonical line") {
            var snap: Map<String, Any> = emptyMap()
            val blockEx = IllegalArgumentException("block blew up")
            val enrichEx = IllegalStateException("enrich blew up")
            val adapter = ThrowingEnrichAdapter(enrichEx)

            val ex = runCatching {
                withCanonicalLogBlocking<String, String>(adapter, "wu", { snap = it.snapshot() }) {
                    throw blockEx
                }
            }.exceptionOrNull()

            adapter.enrichCalls shouldBe 1
            ex shouldBe blockEx
            snap["canonical_log_enrich_error"] shouldBe true
            snap["canonical_log_enrich_error_class"] shouldBe "java.lang.IllegalStateException"
            currentCanonicalContext() shouldBe null
        }
    }

    describe("withCanonicalLog (suspend)") {
        it("propagates context across withContext(Dispatchers.IO)") {
            var snap: Map<String, Any> = emptyMap()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    withContext(Dispatchers.IO) {
                        CanonicalLog.put("from_io", "yes")
                    }
                }
            }
            snap["from_io"] shouldBe "yes"
        }

        it("propagates context to child coroutines and increments accumulate") {
            var snap: Map<String, Any> = emptyMap()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    coroutineScope {
                        val tasks = (1..100).map {
                            async(Dispatchers.IO) { CanonicalLog.increment("parallel_count", 1L) }
                        }
                        tasks.awaitAll()
                    }
                }
            }
            snap["parallel_count"] shouldBe 100L
        }

        it("calls emit exactly once on success") {
            val emitCount = AtomicInteger()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { emitCount.incrementAndGet() }) { "ok" }
            }
            emitCount.get() shouldBe 1
        }

        it("waits for un-joined structured children before emitting: their contributions land") {
            var snap: Map<String, Any> = emptyMap()
            runBlocking {
                withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                    // Deliberately not joined: the delay makes the block return well
                    // before the child writes, so an emit that doesn't await children
                    // would reliably miss the contribution.
                    launch(Dispatchers.IO) {
                        delay(100)
                        CanonicalLog.put("unjoined_child", "yes")
                    }
                    "ok"
                }
            }
            snap["unjoined_child"] shouldBe "yes"
        }

        it("a failing un-joined structured child yields Outcome.Threw, not a Completed line plus an exception") {
            var snap: Map<String, Any> = emptyMap()
            val ex = runCatching {
                runBlocking {
                    withCanonicalLog(nullAdapter, "wu", { snap = it.snapshot() }) {
                        launch(Dispatchers.IO) {
                            delay(100)
                            error("child boom")
                        }
                        "ok"
                    }
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<IllegalStateException>()
            ex.message shouldBe "child boom"
            snap["outcome"] shouldBe "Threw"
        }

        it("calls emit exactly once on exception and rethrows") {
            val emitCount = AtomicInteger()
            val ex = runCatching {
                runBlocking {
                    withCanonicalLog(nullAdapter, "wu", { emitCount.incrementAndGet() }) {
                        error("boom")
                    }
                }
            }.exceptionOrNull()

            ex.shouldBeInstanceOf<IllegalStateException>()
            ex.message shouldBe "boom"
            emitCount.get() shouldBe 1
        }

        it("calls adapter.enrich exactly once and emits exactly once when the suspend block throws") {
            val adapter = object : WorkUnitAdapter<String> {
                var enrichCalls = 0
                override fun describe(input: String) = WorkUnit(input, "test", Instant.now())
                override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {
                    enrichCalls++
                }
            }
            val emitCount = AtomicInteger()
            runCatching {
                runBlocking {
                    withCanonicalLog<String, Unit>(adapter, "wu", { emitCount.incrementAndGet() }) {
                        error("boom")
                    }
                }
            }
            adapter.enrichCalls shouldBe 1
            emitCount.get() shouldBe 1
        }

        it("a throwing emit (suspend variant) is swallowed: block result returned, WARN logged, threadlocal restored") {
            val appender = attachLibraryWarnAppender()
            try {
                val result = runBlocking {
                    withCanonicalLog<String, String>(
                        nullAdapter,
                        "wu",
                        { throw IllegalStateException("emit blew up") },
                    ) { "ok" }
                }

                result shouldBe "ok"
                threadLocalContext.get() shouldBe null
                val warn = appender.list.single { it.level == Level.WARN }
                warn.formattedMessage shouldContain "wu"
                warn.throwableProxy?.message shouldBe "emit blew up"
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("if both block and adapter.enrich throw in the suspend variant, block exception is primary; enrich captured on the canonical line") {
            var snap: Map<String, Any> = emptyMap()
            val blockEx = IllegalArgumentException("block blew up")
            val enrichEx = IllegalStateException("enrich blew up")
            val adapter = ThrowingEnrichAdapter(enrichEx)

            val ex = runCatching {
                runBlocking {
                    withCanonicalLog<String, String>(adapter, "wu", { snap = it.snapshot() }) {
                        throw blockEx
                    }
                }
            }.exceptionOrNull()

            adapter.enrichCalls shouldBe 1
            ex shouldBe blockEx
            snap["canonical_log_enrich_error"] shouldBe true
            snap["canonical_log_enrich_error_class"] shouldBe "java.lang.IllegalStateException"
        }
    }
})
