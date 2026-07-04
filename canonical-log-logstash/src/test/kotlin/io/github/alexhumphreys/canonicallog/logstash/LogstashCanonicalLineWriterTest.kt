package io.github.alexhumphreys.canonicallog.logstash

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.DelicateCanonicalLogApi
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.canonicalLineMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.time.Instant

/**
 * Read the canonical-line fields back off the log event's logstash [MapEntriesAppendingMarker].
 * Reflection because the marker exposes no public map accessor (only `writeTo(JsonGenerator)`,
 * which would lose Kotlin type fidelity like `Long` vs `Int`).
 */
private fun capturedFields(appender: ListAppender<ILoggingEvent>): Map<String, Any> {
    val event = appender.list.lastOrNull { it.loggerName == "canonical" }
        ?: error("no canonical log event captured")
    val args: Array<out Any?> = event.argumentArray ?: emptyArray()
    val markers: List<Any> = (event.markerList ?: emptyList<Marker>()) + args.filterNotNull()
    return markers.filterIsInstance<MapEntriesAppendingMarker>()
        .map { marker ->
            val mapField = generateSequence<Class<*>>(marker::class.java) { it.superclass }
                .firstNotNullOfOrNull { cls ->
                    cls.declaredFields.firstOrNull { f -> Map::class.java.isAssignableFrom(f.type) }
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

@OptIn(DelicateCanonicalLogApi::class)
class LogstashCanonicalLineWriterTest : DescribeSpec({

    describe("LogstashCanonicalLineWriter") {

        it("emits one event with the human message and every field attached as a structured argument, types preserved") {
            val appender = attachAppender()
            try {
                val ctx = CanonicalLogContext(WorkUnit("wu-1", "scheduled_job", Instant.now()))
                ctx.put("job_name", "ReportingJob.run")
                ctx.increment("db_query_count", 3L)

                LogstashCanonicalLineWriter().write(ctx)

                appender.list.count { it.loggerName == "canonical" } shouldBe 1
                appender.list.single().message shouldBe canonicalLineMessage(ctx.snapshot())
                val fields = capturedFields(appender)
                fields["job_name"] shouldBe "ReportingJob.run"
                // Unlike the MDC writer, the counter stays a Long — type fidelity preserved.
                fields["db_query_count"] shouldBe 3L
            } finally {
                detachAppender(appender)
            }
        }
    }
})
