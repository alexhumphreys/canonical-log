package io.github.alexhumphreys.canonicallog.scheduling

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The 019 regression pin: a user [CanonicalLineWriter] bean receives scheduled-job lines. The
 * scheduling starter no longer hardcodes its logstash emit — it injects the shared writer bean —
 * so overriding the sink for HTTP lines overrides it for scheduled jobs too.
 */
class CanonicalSchedulingWriterOverrideTest : DescribeSpec({

    var app: ConfigurableApplicationContext? = null

    afterSpec { app?.close() }

    describe("scheduling writer override") {
        it("routes scheduled-job lines through a user CanonicalLineWriter bean") {
            app = SpringApplicationBuilder(WriterOverrideTestApp::class.java)
                .web(WebApplicationType.NONE)
                .run()
            val writer = app!!.getBean(CapturingLineWriter::class.java)

            val snap = awaitCaptured(writer)
                ?: error("user writer bean received no scheduled-job line within the timeout")

            snap["work_unit_kind"] shouldBe "scheduled_job"
            snap["job_name"] shouldBe "OverrideTickerJob.tick"
            (snap["work_unit_id"] as String).shouldNotBeEmpty()
            snap["tick_field"] shouldBe "on"
        }
    }
})

/** User sink bean: records snapshots instead of logging. */
class CapturingLineWriter : CanonicalLineWriter {
    val snapshots = CopyOnWriteArrayList<Map<String, Any>>()
    override fun write(context: CanonicalLogContext) {
        snapshots += context.snapshot()
    }
}

@Configuration
@EnableAutoConfiguration
@EnableScheduling
open class WriterOverrideTestApp {
    @Bean
    open fun capturingLineWriter(): CapturingLineWriter = CapturingLineWriter()

    @Bean
    open fun overrideTickerJob(): OverrideTickerJob = OverrideTickerJob()
}

open class OverrideTickerJob {
    @Scheduled(fixedDelayString = "100", initialDelayString = "50")
    open fun tick() {
        CanonicalLog.put("tick_field", "on")
    }
}

private fun awaitCaptured(
    writer: CapturingLineWriter,
    timeoutMs: Long = 5_000,
): Map<String, Any>? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val hit = writer.snapshots.firstOrNull { it["work_unit_kind"] == "scheduled_job" }
        if (hit != null) return hit
        Thread.sleep(50)
    }
    return null
}
