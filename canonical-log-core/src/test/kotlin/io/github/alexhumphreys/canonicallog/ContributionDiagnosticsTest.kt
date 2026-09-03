package io.github.alexhumphreys.canonicallog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val diagnosticsAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

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

/**
 * The two opt-in diagnostics for contributions that go missing (todos 044 and 049).
 *
 * Both failure modes are silent by design in production — telemetry must never fail the
 * operation it observes — so what is pinned here is that (a) the default really is silent and
 * free, and (b) opting in makes each one loud, for the *right* one of the two cases:
 * `onUnboundContribution` fires when nothing was bound (the write never happened),
 * `onLateWrite` when a captured context reference was written after its line was emitted (the
 * write happened and was lost anyway).
 */
@OptIn(DelicateCanonicalLogApi::class)
class ContributionDiagnosticsTest : DescribeSpec({

    afterEach {
        threadLocalContext.set(null)
        CanonicalLog.onUnboundContribution = null
        CanonicalLog.onLateWrite = null
    }

    describe("production default") {
        it("both hooks are null, so an unbound contribution stays a silent no-op") {
            CanonicalLog.onUnboundContribution shouldBe null
            CanonicalLog.onLateWrite shouldBe null
            // Must not throw with nothing bound.
            CanonicalLog.put("k", "v")
            CanonicalLog.increment("c")
            CanonicalLog.markFailed("boom")
            CanonicalLog.markDegraded("slow")
        }
    }

    describe("onUnboundContribution") {
        it("fires once per ambient entry point when nothing is bound, naming the key") {
            val keys = mutableListOf<String>()
            CanonicalLog.onUnboundContribution = { keys += it }

            CanonicalLog.put("some_field", "v")
            CanonicalLog.increment("some_counter")
            CanonicalLog.markFailed("boom")
            CanonicalLog.markFailed("boom", mapOf("extra" to 1L))
            CanonicalLog.markDegraded("slow")
            CanonicalLog.markDegraded("slow", mapOf("extra" to 1L))

            keys shouldContainExactly listOf(
                "some_field",
                "some_counter",
                CanonicalFields.ERROR,
                CanonicalFields.ERROR,
                CanonicalFields.DEGRADED,
                CanonicalFields.DEGRADED,
            )
        }

        it("never fires while a unit is bound") {
            var fired = 0
            CanonicalLog.onUnboundContribution = { fired++ }

            withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = {}) {
                CanonicalLog.put("inside", "yes")
                CanonicalLog.increment("inside_count")
                CanonicalLog.markDegraded("slow")
            }

            fired shouldBe 0
        }

        it("fires for a contribution made from inside a top-level emit (the deliberately-unbound window)") {
            val keys = mutableListOf<String>()
            CanonicalLog.onUnboundContribution = { keys += it }

            withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = { CanonicalLog.put("from_sink", "x") }) {}

            // Emit runs with the finalized unit unbound on every entry point, so a sink that
            // contributes ambiently is reported like any other unbound contribution —
            // contributing from a sink is discouraged, and this is how a strict test hears it.
            keys shouldContainExactly listOf("from_sink")
        }

        it("catches a wrong-thread hop, and stays quiet when the hop is wrapped") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val unwrapped = mutableListOf<String>()
                CanonicalLog.onUnboundContribution = { unwrapped += it }
                withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = {}) {
                    pool.submit { CanonicalLog.put("from_pool", "x") }.get(10, TimeUnit.SECONDS)
                }
                unwrapped shouldContainExactly listOf("from_pool")

                val wrapped = mutableListOf<String>()
                CanonicalLog.onUnboundContribution = { wrapped += it }
                var line: Map<String, Any> = emptyMap()
                withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = { line = it.snapshot() }) {
                    pool.submit(Runnable { CanonicalLog.put("from_pool", "x") }.propagatingCanonicalContext())
                        .get(10, TimeUnit.SECONDS)
                }
                wrapped.isEmpty() shouldBe true
                line["from_pool"] shouldBe "x"
            } finally {
                pool.shutdownNow()
            }
        }
    }

    describe("onLateWrite / canonical_log_late_write_count") {
        it("a write through a captured context after emit is counted, WARN'd, and absent from the line") {
            val appender = attachLibraryWarnAppender()
            try {
                val late = mutableListOf<Pair<String, String>>()
                CanonicalLog.onLateWrite = { id, key -> late += id to key }

                var line: Map<String, Any> = emptyMap()
                var captured: CanonicalLogContext? = null
                withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = { line = it.snapshot() }) { ctx ->
                    captured = ctx
                }
                val ctx = checkNotNull(captured)
                ctx.put("too_late", "x")

                line.containsKey("too_late") shouldBe false
                line.containsKey(CanonicalFields.LATE_WRITE_COUNT) shouldBe false
                ctx.get("too_late") shouldBe "x"
                ctx.get(CanonicalFields.LATE_WRITE_COUNT) shouldBe 1L
                late shouldContainExactly listOf("wu" to "too_late")

                val warn = appender.list.single { it.level == Level.WARN }
                warn.formattedMessage shouldContain "too_late"
                warn.formattedMessage shouldContain "wu"
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("repeated late writes keep counting but WARN only once") {
            val appender = attachLibraryWarnAppender()
            try {
                var captured: CanonicalLogContext? = null
                withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = {}) { ctx -> captured = ctx }
                val ctx = checkNotNull(captured)

                ctx.put("a", 1L)
                ctx.increment("b")
                ctx.markDegraded("slow") // two puts → two late writes

                ctx.get(CanonicalFields.LATE_WRITE_COUNT) shouldBe 4L
                appender.list.count { it.level == Level.WARN } shouldBe 1
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("counts concurrent late writes exactly") {
            val threads = 8
            val perThread = 250
            val pool = Executors.newFixedThreadPool(threads)
            val appender = attachLibraryWarnAppender()
            try {
                val reported = AtomicLong(0)
                CanonicalLog.onLateWrite = { _, _ -> reported.incrementAndGet() }

                var captured: CanonicalLogContext? = null
                withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = {}) { ctx -> captured = ctx }
                val ctx = checkNotNull(captured)

                val start = CountDownLatch(1)
                val futures = (0 until threads).map { t ->
                    pool.submit {
                        start.await()
                        repeat(perThread) { i -> ctx.put("k$t$i", i.toLong()) }
                    }
                }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }

                ctx.get(CanonicalFields.LATE_WRITE_COUNT) shouldBe (threads * perThread).toLong()
                reported.get() shouldBe (threads * perThread).toLong()
                appender.list.count { it.level == Level.WARN } shouldBe 1
            } finally {
                pool.shutdownNow()
                detachLibraryWarnAppender(appender)
            }
        }

        it("is invisible on the happy path: no counter, no WARN, no hook call") {
            val appender = attachLibraryWarnAppender()
            try {
                var fired = 0
                CanonicalLog.onLateWrite = { _, _ -> fired++ }

                var line: Map<String, Any> = emptyMap()
                withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = { line = it.snapshot() }) { ctx ->
                    ctx.put("during", "x")
                    CanonicalLog.increment("during_count")
                }

                line["during"] shouldBe "x"
                line.containsKey(CanonicalFields.LATE_WRITE_COUNT) shouldBe false
                fired shouldBe 0
                appender.list.count { it.level == Level.WARN } shouldBe 0
            } finally {
                detachLibraryWarnAppender(appender)
            }
        }

        it("a late increment onto a non-Long still reports the late write, not just the type conflict") {
            var captured: CanonicalLogContext? = null
            withCanonicalLogBlocking(diagnosticsAdapter, "wu", emit = {}) { ctx ->
                ctx.put("clash", "string")
                captured = ctx
            }
            val ctx = checkNotNull(captured)
            ctx.increment("clash")

            // One late write for the increment itself; the conflict markers the library writes
            // in response are not counted as further contributions.
            ctx.get(CanonicalFields.LATE_WRITE_COUNT) shouldBe 1L
            ctx.get(CanonicalFields.TYPE_CONFLICT) shouldBe true
        }
    }
})
