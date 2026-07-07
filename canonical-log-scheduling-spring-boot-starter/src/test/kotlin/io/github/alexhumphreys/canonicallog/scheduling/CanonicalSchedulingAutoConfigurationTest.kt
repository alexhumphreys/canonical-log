package io.github.alexhumphreys.canonicallog.scheduling

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.test.RecordingCanonicalAppender
import io.github.alexhumphreys.canonicallog.test.canonicalFields
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Boots a real (non-web) Spring context with `@EnableScheduling` and asserts the scheduling
 * auto-config transparently produces one canonical line per run — proving the observation hook
 * binds a work unit around a plain `@Scheduled` method with no wrapping in the body, and that a
 * body-side `CanonicalLog.put` lands on that line.
 *
 * The ticker fires exactly once (long fixed delay) so the shared-appender read has a single,
 * deterministic scheduled-job line to match — [RecordingCanonicalAppender.awaitLine] observes it
 * off the scheduler thread (await + snapshot, no `ConcurrentModificationException`) and asserts
 * exactly one.
 */
class CanonicalSchedulingAutoConfigurationTest : DescribeSpec({

    var app: ConfigurableApplicationContext? = null
    lateinit var appender: RecordingCanonicalAppender

    beforeSpec {
        app = SpringApplicationBuilder(SchedulingTestApp::class.java)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .run()
        // Attach AFTER run() so boot's logback init doesn't clear the appender.
        appender = RecordingCanonicalAppender.attach()
    }

    afterSpec {
        app?.close()
        appender.close()
    }

    describe("scheduled task instrumentation") {
        it("emits a canonical line per run with job identity and a body-contributed field") {
            val snap = appender.awaitLine { canonicalFields(it)["work_unit_kind"] == "scheduled_job" }

            snap["work_unit_kind"] shouldBe "scheduled_job"
            snap["job_name"] shouldBe "TickerJob.tick"
            (snap["work_unit_id"] as String).shouldNotBeEmpty()
            (snap["job_duration_ms"] as Long).shouldBeGreaterThanOrEqual(0L)
            // The body's CanonicalLog.put landed — the work unit was bound during the method.
            snap["tick_field"] shouldBe "on"
            snap.containsKey("error") shouldBe false
        }

        it("emits a human-readable message for the non-HTTP work unit") {
            appender.awaitLine { canonicalFields(it)["work_unit_kind"] == "scheduled_job" }
            val event = appender.events().last { it.loggerName == RecordingCanonicalAppender.CANONICAL_LOGGER_NAME }
            // Non-HTTP shape: "<work_unit_kind> <work_unit_id>".
            event.formattedMessage shouldStartWith "scheduled_job "
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
    // Fire once, well after the appender is attached: a single deterministic canonical line lets
    // the shared-appender read assert exactly-one (a repeating ticker would produce many
    // identical scheduled_job lines and defeat that check). The long fixed delay parks the next
    // run an hour out, past the test window.
    @Scheduled(fixedDelayString = "3600000", initialDelayString = "300")
    open fun tick() {
        CanonicalLog.put("tick_field", "on")
    }
}
