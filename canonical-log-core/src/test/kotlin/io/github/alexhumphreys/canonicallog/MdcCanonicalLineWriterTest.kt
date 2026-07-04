package io.github.alexhumphreys.canonicallog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

/**
 * Captures events and forces each one's MDC snapshot *at append time* — while the writer still
 * has the fields in MDC. A plain `ListAppender` reads `getMDCPropertyMap()` lazily, which the
 * writer's `finally` block would have already unwound, so it would see an empty/restored MDC.
 */
private class MdcCapturingAppender : AppenderBase<ILoggingEvent>() {
    val events = mutableListOf<ILoggingEvent>()
    override fun append(event: ILoggingEvent) {
        event.mdcPropertyMap // force the snapshot to cache into the event now
        events += event
    }
}

private fun attachAppender(): MdcCapturingAppender {
    val appender = MdcCapturingAppender().also { it.start() }
    val logger = LoggerFactory.getLogger("canonical") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.INFO
    return appender
}

private fun detachAppender(appender: MdcCapturingAppender) {
    (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
}

@OptIn(DelicateCanonicalLogApi::class)
class MdcCanonicalLineWriterTest : DescribeSpec({

    describe("MdcCanonicalLineWriter") {

        it("emits one event per write with the canonicalLineMessage text and every field flattened into MDC") {
            val appender = attachAppender()
            try {
                val ctx = CanonicalLogContext(WorkUnit("wu-1", "scheduled_job", Instant.now()))
                ctx.put("job_name", "ReportingJob.run")
                ctx.increment("db_query_count", 3L)

                MdcCanonicalLineWriter().write(ctx)

                appender.events.size shouldBe 1
                val event = appender.events.single()
                event.message shouldBe canonicalLineMessage(ctx.snapshot())
                // Every snapshot field is present in the event's captured MDC, stringified.
                val expected = ctx.snapshot().mapValues { it.value.toString() }
                event.mdcPropertyMap shouldContainAll expected
                // The counter arrived as a String, not a Long — the documented trade-off.
                event.mdcPropertyMap["db_query_count"] shouldBe "3"
            } finally {
                detachAppender(appender)
            }
        }

        it("restores a pre-existing MDC value under a colliding key after write") {
            val appender = attachAppender()
            MDC.put("work_unit_id", "outer-correlation")
            try {
                val ctx = CanonicalLogContext(WorkUnit("wu-2", "scheduled_job", Instant.now()))
                ctx.put("work_unit_id", "wu-2")

                MdcCanonicalLineWriter().write(ctx)

                MDC.get("work_unit_id") shouldBe "outer-correlation"
            } finally {
                MDC.remove("work_unit_id")
                detachAppender(appender)
            }
        }

        it("leaves a key absent from MDC before write absent after write") {
            val appender = attachAppender()
            try {
                val ctx = CanonicalLogContext(WorkUnit("wu-3", "scheduled_job", Instant.now()))
                ctx.put("job_name", "ReportingJob.run")

                MdcCanonicalLineWriter().write(ctx)

                MDC.get("job_name").shouldBeNull()
            } finally {
                detachAppender(appender)
            }
        }

        it("restores MDC even when the message function throws") {
            val appender = attachAppender()
            try {
                val ctx = CanonicalLogContext(WorkUnit("wu-4", "scheduled_job", Instant.now()))
                ctx.put("job_name", "ReportingJob.run")
                val writer = MdcCanonicalLineWriter(message = { error("boom") })

                shouldThrow<IllegalStateException> { writer.write(ctx) }

                // finally-block restored the displaced (absent) value.
                MDC.get("job_name").shouldBeNull()
            } finally {
                detachAppender(appender)
            }
        }
    }
})
