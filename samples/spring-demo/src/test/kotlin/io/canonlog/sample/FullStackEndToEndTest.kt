package io.canonlog.sample

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.concurrent.TimeUnit

/**
 * End-to-end test of the full canonical-log stack:
 *
 * - HTTP filter creates the work unit on `/posts/{id}/external`.
 * - JDBC contributor sees the post-title `SELECT`.
 * - OkHttp customizer applies the canonical interceptor.
 * - OkHttp interceptor records the outbound call.
 * - Accumulator collects everything; filter emits one line.
 *
 * The upstream is a real `mccutchen/go-httpbin` container — chosen over an
 * in-process MockWebServer specifically because this test exists to prove the
 * customizer wiring through a real HTTP round-trip. If any seam is broken (e.g.
 * the customizer isn't applied, the interceptor isn't on the chain, the bridge
 * loses the threadlocal for an outbound call), this test fails.
 */
class FullStackEndToEndTest : DescribeSpec({

    val httpbin = GenericContainer("mccutchen/go-httpbin:2.22.1")
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/").forStatusCode(200))

    var app: ConfigurableApplicationContext? = null
    var port = 0
    val appender = ListAppender<ILoggingEvent>()
    val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    beforeSpec {
        httpbin.start()

        app = SpringApplication.run(
            Application::class.java,
            "--server.port=0",
            "--canonlog.sample.upstream.url=http://${httpbin.host}:${httpbin.firstMappedPort}",
            "--spring.datasource.url=jdbc:h2:mem:e2e-${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        port = app!!.environment.getProperty("local.server.port", Int::class.java)!!

        // Attach the appender AFTER Spring boots — Spring Boot resets logback during
        // initialization from logback-spring.xml, which would clear an earlier
        // attachment.
        appender.start()
        val canonical = LoggerFactory.getLogger("canonical") as LogbackLogger
        canonical.addAppender(appender)
        canonical.level = Level.INFO
    }

    afterSpec {
        app?.close()
        (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
        httpbin.stop()
    }

    describe("Full-stack end-to-end") {
        it("HTTP + JDBC + OkHttp customizer + interceptor + accumulator + emit produce one line with all field families") {
            appender.list.clear()

            client.newCall(
                Request.Builder().url("http://localhost:$port/posts/1/external").build(),
            ).execute().use { it.code shouldBe 200 }

            val snap = lastCanonicalSnapshot(appender)
                ?: error("no canonical line was emitted")

            // HTTP server fields — proves the filter ran and the matched route was captured.
            snap["http_route"] shouldBe "/posts/{id}/external"
            snap["url_path"] shouldBe "/posts/1/external"
            snap["http_response_status_code"] shouldBe 200
            snap["http_request_method"] shouldBe "GET"

            // OkHttp client fields — proves the customizer was applied and the interceptor saw the call.
            (snap["http_client_request_count"] as Long).shouldBeGreaterThanOrEqual(1L)
            (snap["http_client_request_duration_ms_total"] as Long).shouldBeGreaterThanOrEqual(0L)

            // JDBC fields — proves the BPP wrapped the auto-configured DataSource.
            (snap["db_query_count"] as Long).shouldBeGreaterThanOrEqual(1L)
            (snap["db_execution_count"] as Long).shouldBeGreaterThanOrEqual(1L)

            // Lifecycle fields — proves the work unit was opened and emitted.
            (snap["work_unit_id"] as String).shouldNotBeEmpty()
            snap["work_unit_kind"] shouldBe "http"

            // Handler-supplied fields — proves CanonicalLog.put landed during the request.
            snap["post_id"] shouldBe 1L
        }
    }
})
