package io.github.alexhumphreys.canonicallog.dropwizard

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.dropwizard.core.Configuration
import io.dropwizard.testing.ConfigOverride
import io.dropwizard.testing.DropwizardTestSupport
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder
import org.slf4j.LoggerFactory

class CanonicalLogBundleTest : DescribeSpec({

    val support = DropwizardTestSupport(
        TestApplication::class.java,
        null as String?,
        ConfigOverride.config("server.applicationConnectors[0].port", "0"),
        ConfigOverride.config("server.adminConnectors[0].port", "0"),
        // Let a thrown resource exception propagate to the servlet filter (Outcome.Threw →
        // error_class) instead of being mapped to a 500 JSON body by Dropwizard's default
        // exception mappers. See the finding documented in the module KDoc/README.
        ConfigOverride.config("server.registerDefaultExceptionMappers", "false"),
    )

    // Attach the appender AFTER boot: Dropwizard re-reads its logging config during
    // support.before(), which would clear an appender attached earlier (same logback-init
    // gotcha the Spring sample documents).
    lateinit var appender: ListAppender<ILoggingEvent>
    lateinit var client: Client
    lateinit var baseUri: String

    beforeSpec {
        support.before()
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
        root.addAppender(appender)
        root.level = Level.INFO
        client = ClientBuilder.newClient()
        baseUri = "http://localhost:${support.localPort}"
    }

    afterSpec {
        client.close()
        (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger).detachAppender(appender)
        support.after()
    }

    fun canonicalEvents(): List<ILoggingEvent> = appender.list.filter { it.loggerName == "canonical" }
    fun canonicalFields(): Map<String, String> = canonicalEvents().single().mdcPropertyMap

    describe("CanonicalLogBundle") {

        it("emits exactly one canonical line with route template, path, method, status, duration for a success") {
            appender.list.clear()
            val status = client.target(baseUri).path("/posts/7").request().get().let { it.status.also { _ -> it.close() } }
            status shouldBe 200

            canonicalEvents().size shouldBe 1
            val fields = canonicalFields()
            fields["http_route"] shouldBe "/posts/{id}"
            fields["url_path"] shouldBe "/posts/7"
            fields["http_request_method"] shouldBe "GET"
            fields["http_response_status_code"] shouldBe "200"
            fields["http_request_duration_ms"].shouldNotBeNull()
            fields shouldContainKey "work_unit_id"

            // Negative assertions: no error markers, no self-diagnostics on a clean success.
            fields shouldNotContainKey "error"
            fields.keys.none { it.startsWith("canonical_log_") } shouldBe true
        }

        it("reconstructs the sub-resource route template most-specific-last") {
            appender.list.clear()
            val status = client.target(baseUri).path("/posts/7/comments/3").request().get()
                .let { it.status.also { _ -> it.close() } }
            status shouldBe 200

            canonicalEvents().size shouldBe 1
            val fields = canonicalFields()
            // Empirically pinned: ExtendedUriInfo.matchedTemplates is most-specific-first, so
            // the reversed join reconstructs the full multi-segment route in reading order.
            fields["http_route"] shouldBe "/posts/{id}/comments/{commentId}"
            fields["url_path"] shouldBe "/posts/7/comments/3"
        }

        it("marks a business failure (markFailed) with error+reason but no error_class") {
            appender.list.clear()
            val status = client.target(baseUri).path("/posts/999").request().get()
                .let { it.status.also { _ -> it.close() } }
            status shouldBe 404

            canonicalEvents().size shouldBe 1
            val fields = canonicalFields()
            fields["error"] shouldBe "true"
            fields["error_reason"] shouldBe "post_not_found"
            fields["http_response_status_code"] shouldBe "404"
            fields shouldNotContainKey "error_class"
        }

        it("emits an error line with error_class and status 500 for a thrown exception") {
            appender.list.clear()
            val status = client.target(baseUri).path("/posts/7/boom").request().get()
                .let { it.status.also { _ -> it.close() } }

            status shouldBe 500

            canonicalEvents().size shouldBe 1
            val fields = canonicalFields()
            fields["error"] shouldBe "true"
            fields["error_reason"] shouldBe "exception"
            // The servlet container wraps the resource exception before it reaches the
            // filter, so error_class is the wrapper (jakarta.servlet.ServletException),
            // not the original IllegalStateException — the Threw path is still exercised.
            fields shouldContainKey "error_class"
            fields["http_response_status_code"] shouldBe "500"
        }

        it("emits zero lines for an excluded application-port path") {
            appender.list.clear()
            client.target(baseUri).path("/ping").request().get().close()
            canonicalEvents().size shouldBe 0
        }

        it("carries work_unit_id in the MDC of an ordinary handler log line during the request") {
            appender.list.clear()
            client.target(baseUri).path("/posts/7").request().get().close()

            val handlerEvent = appender.list.single { it.loggerName == "test-handler" }
            handlerEvent.mdcPropertyMap shouldContainKey "work_unit_id"
        }
    }
})
