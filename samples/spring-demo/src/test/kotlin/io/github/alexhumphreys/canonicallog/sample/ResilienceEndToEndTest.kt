package io.github.alexhumphreys.canonicallog.sample

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import java.util.concurrent.TimeUnit

/**
 * End-to-end proof that `canonical-log-resilience4j-spring-boot-starter` wires itself into a
 * real app: registry beans in, retry/rejection fields out, with the controller never naming a
 * canonical field. Deliberately container-free — the flaky upstream is the app's own
 * in-process MockWebServer, so this stays a fast test of the *wiring*.
 */
class ResilienceEndToEndTest : DescribeSpec({

    var app: ConfigurableApplicationContext? = null
    var port = 0
    val appender = ListAppender<ILoggingEvent>()
    val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    beforeSpec {
        app = SpringApplication.run(
            Application::class.java,
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:resilience-${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        port = app!!.environment.getProperty("local.server.port", Int::class.java)!!

        // Attach after boot — Spring Boot resets logback from logback-spring.xml.
        appender.start()
        val canonical = LoggerFactory.getLogger("canonical") as LogbackLogger
        canonical.addAppender(appender)
        canonical.level = Level.INFO
    }

    afterSpec {
        app?.close()
        (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
    }

    fun call(path: String) = client.newCall(
        Request.Builder().url("http://localhost:$port$path").build(),
    ).execute().use { it.code }

    describe("resilience fields end-to-end") {

        it("a retried-then-successful request carries its attempts and backoff") {
            appender.list.clear()
            // The upstream 500s twice per fresh path, so the Retry's third attempt succeeds.
            call("/posts/1/flaky") shouldBe 200

            val snap = lastCanonicalSnapshot(appender) ?: error("no canonical line was emitted")

            snap[CanonicalFields.RETRY_ATTEMPT_COUNT] shouldBe 2L
            (snap[CanonicalFields.RETRY_WAIT_DURATION_MS_TOTAL] as Long)
                .shouldBeGreaterThanOrEqual(50L)
            // Three outbound calls really happened — this is the "slow because we retried"
            // reading the duration alone can't give.
            (snap["http_client_request_count"] as Long).shouldBeGreaterThanOrEqual(3L)
            snap[CanonicalFields.CIRCUIT_BREAKER_FAILURE_COUNT] shouldBe 2L
            // Succeeded in the end: not an error, not shed.
            snap shouldNotContainKey CanonicalFields.RESILIENCE_REJECTED
            snap shouldNotContainKey CanonicalFields.ERROR
        }

        it("once the breaker opens, requests are shed with no outbound call at all") {
            // Each fresh path fails twice, which is what drives the failure rate up.
            repeat(6) { call("/posts/${100 + it}/flaky") }
            appender.list.clear()

            call("/posts/999/flaky") shouldBe 503

            val snap = lastCanonicalSnapshot(appender) ?: error("no canonical line was emitted")

            snap[CanonicalFields.RESILIENCE_REJECTED] shouldBe true
            (snap[CanonicalFields.CIRCUIT_BREAKER_REJECTED_COUNT] as Long)
                .shouldBeGreaterThanOrEqual(1L)
            snap[CanonicalFields.CIRCUIT_BREAKER_OPEN_NAME] shouldBe "sample-upstream"
            // The load never reached the upstream — the whole point of the flag.
            snap shouldNotContainKey "http_client_request_count"
            snap["error_reason"] shouldBe "upstream_circuit_open"
        }
    }
})
