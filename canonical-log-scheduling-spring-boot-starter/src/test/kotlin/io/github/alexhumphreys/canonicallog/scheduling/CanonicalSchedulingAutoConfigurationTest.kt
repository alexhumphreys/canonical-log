package io.github.alexhumphreys.canonicallog.scheduling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Boots a real (non-web) Spring context with `@EnableScheduling` and a fast ticker, and asserts
 * the scheduling auto-config transparently produces one canonical line per run — proving the
 * observation hook binds a work unit around a plain `@Scheduled` method with no wrapping in the
 * body, and that a body-side `CanonicalLog.put` lands on that line.
 */
class CanonicalSchedulingAutoConfigurationTest : DescribeSpec({

    var app: ConfigurableApplicationContext? = null
    val appender = ListAppender<ILoggingEvent>()

    beforeSpec {
        app = SpringApplicationBuilder(SchedulingTestApp::class.java)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .run()
        appender.start()
        val canonical = LoggerFactory.getLogger("canonical") as LogbackLogger
        canonical.addAppender(appender)
        canonical.level = Level.INFO
    }

    afterSpec {
        app?.close()
        (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
    }

    describe("scheduled task instrumentation") {
        it("emits a canonical line per run with job identity and a body-contributed field") {
            val snap = awaitScheduledLine(appender)
                ?: error("no scheduled-job canonical line was emitted within the timeout")

            snap["work_unit_kind"] shouldBe "scheduled_job"
            snap["job_name"] shouldBe "TickerJob.tick"
            (snap["work_unit_id"] as String).shouldNotBeEmpty()
            (snap["job_duration_ms"] as Long).shouldBeGreaterThanOrEqual(0L)
            // The body's CanonicalLog.put landed — the work unit was bound during the method.
            snap["tick_field"] shouldBe "on"
            snap.containsKey("error") shouldBe false
        }
    }
})

@Configuration
@EnableAutoConfiguration
@EnableScheduling
open class SchedulingTestApp {
    // Registered explicitly rather than via @ComponentScan so the bean is found without a
    // scan base package, and @Scheduled processing picks it up.
    @Bean
    open fun tickerJob(): TickerJob = TickerJob()
}

open class TickerJob {
    @Scheduled(fixedDelayString = "100", initialDelayString = "50")
    open fun tick() {
        CanonicalLog.put("tick_field", "on")
    }
}

private fun awaitScheduledLine(
    appender: ListAppender<ILoggingEvent>,
    timeoutMs: Long = 5_000,
): Map<String, Any>? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val hit = appender.list
            .filter { it.loggerName == "canonical" }
            .map(::snapshotOf)
            .firstOrNull { it["work_unit_kind"] == "scheduled_job" }
        if (hit != null) return hit
        Thread.sleep(50)
    }
    return null
}

private fun snapshotOf(event: ILoggingEvent): Map<String, Any> {
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
        .fold(emptyMap()) { acc, m -> acc + m }
}
