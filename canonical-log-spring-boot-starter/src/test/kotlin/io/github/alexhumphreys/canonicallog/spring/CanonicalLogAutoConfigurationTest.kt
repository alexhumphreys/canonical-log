package io.github.alexhumphreys.canonicallog.spring

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** Test writer that keeps snapshots in memory instead of logging. */
private class CapturingLineWriter : CanonicalLineWriter {
    val snapshots = mutableListOf<Map<String, Any>>()
    override fun write(context: CanonicalLogContext) {
        snapshots += context.snapshot()
    }
}

/** Sampler bean that keeps only lines carrying `error=true`. */
private class ErrorsOnlySampler : CanonicalLogSampler {
    override fun shouldEmit(context: CanonicalLogContext): Boolean =
        context.snapshot()["error"] == true
}

/**
 * The compose-don't-subclass pattern from the filter KDoc: delegate describe/enrich
 * to [HttpWorkUnitAdapter], then put extra uniform fields.
 */
private class TenantWorkUnitAdapter : WorkUnitAdapter<HttpExchange> {
    private val delegate = HttpWorkUnitAdapter()
    override fun describe(input: HttpExchange): WorkUnit = delegate.describe(input)
    override fun enrich(ctx: CanonicalLogContext, input: HttpExchange, outcome: Outcome) {
        delegate.enrich(ctx, input, outcome)
        ctx.put("tenant_id", "acme")
    }
}

class CanonicalLogAutoConfigurationTest : DescribeSpec({
    val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CanonicalLogAutoConfiguration::class.java))

    describe("CanonicalLogAutoConfiguration") {
        it("registers the filter and a registration bean in a servlet context") {
            runner.run { ctx ->
                ctx.getBeansOfType(CanonicalLogFilter::class.java).size shouldBe 1
                @Suppress("UNCHECKED_CAST")
                val regs = ctx.getBeansOfType(FilterRegistrationBean::class.java)
                    as Map<String, FilterRegistrationBean<*>>
                regs.values.any { it.filter is CanonicalLogFilter } shouldBe true
            }
        }

        it("backs off when a CanonicalLogFilter bean already exists") {
            runner.withBean("custom", CanonicalLogFilter::class.java).run { ctx ->
                ctx.getBeansOfType(CanonicalLogFilter::class.java).size shouldBe 1
                ctx.containsBean("custom") shouldBe true
                ctx.containsBean("canonicalLogFilter") shouldBe false
            }
        }

        it("opts out when canonical-log.http.enabled=false") {
            runner.withPropertyValues("canonical-log.http.enabled=false").run { ctx ->
                ctx.getBeansOfType(CanonicalLogFilter::class.java).size shouldBe 0
                ctx.getBeansOfType(FilterRegistrationBean::class.java).size shouldBe 0
            }
        }

        it("injects a user CanonicalLineWriter bean into the filter") {
            runner.withBean(CapturingLineWriter::class.java).run { ctx ->
                val appender = ListAppender<ILoggingEvent>().also { it.start() }
                val canonicalLogger = LoggerFactory.getLogger("canonical") as LogbackLogger
                canonicalLogger.addAppender(appender)
                canonicalLogger.level = Level.INFO
                try {
                    val req = MockHttpServletRequest("GET", "/custom-writer")
                    val res = MockHttpServletResponse().apply { status = 200 }
                    ctx.getBean(CanonicalLogFilter::class.java).doFilter(req, res) { _, _ -> }

                    val writer = ctx.getBean(CapturingLineWriter::class.java)
                    writer.snapshots.size shouldBe 1
                    writer.snapshots.single()["url_path"] shouldBe "/custom-writer"
                    // The default logstash sink must be fully replaced, not doubled up.
                    appender.list.count { it.loggerName == "canonical" } shouldBe 0
                } finally {
                    canonicalLogger.detachAppender(appender)
                }
            }
        }

        it("injects a user WorkUnitAdapter<HttpExchange> bean into the filter") {
            runner
                .withBean(TenantWorkUnitAdapter::class.java)
                .withBean(CapturingLineWriter::class.java)
                .run { ctx ->
                    val req = MockHttpServletRequest("GET", "/custom-adapter")
                    val res = MockHttpServletResponse().apply { status = 200 }
                    ctx.getBean(CanonicalLogFilter::class.java).doFilter(req, res) { _, _ -> }

                    val snapshot = ctx.getBean(CapturingLineWriter::class.java).snapshots.single()
                    snapshot["tenant_id"] shouldBe "acme"
                    // Delegation to HttpWorkUnitAdapter still supplies the uniform HTTP fields.
                    snapshot["http_request_method"] shouldBe "GET"
                    snapshot["http_response_status_code"] shouldBe 200
                }
        }

        it("binds canonical-log.http.exclude-paths and excludes matching requests") {
            runner
                .withPropertyValues("canonical-log.http.exclude-paths=/actuator/**")
                .withBean(CapturingLineWriter::class.java)
                .run { ctx ->
                    val filter = ctx.getBean(CanonicalLogFilter::class.java)
                    val writer = ctx.getBean(CapturingLineWriter::class.java)

                    val probe = MockHttpServletRequest("GET", "/actuator/health")
                    filter.doFilter(probe, MockHttpServletResponse().apply { status = 200 }) { _, _ -> }
                    writer.snapshots.size shouldBe 0

                    val real = MockHttpServletRequest("GET", "/posts/1")
                    filter.doFilter(real, MockHttpServletResponse().apply { status = 200 }) { _, _ -> }
                    writer.snapshots.size shouldBe 1
                    writer.snapshots.single()["url_path"] shouldBe "/posts/1"
                }
        }

        it("injects a user CanonicalLogSampler bean into the filter") {
            runner
                .withBean(ErrorsOnlySampler::class.java)
                .withBean(CapturingLineWriter::class.java)
                .run { ctx ->
                    val filter = ctx.getBean(CanonicalLogFilter::class.java)
                    val writer = ctx.getBean(CapturingLineWriter::class.java)

                    val req = MockHttpServletRequest("GET", "/healthy-200")
                    filter.doFilter(req, MockHttpServletResponse().apply { status = 200 }) { _, _ -> }
                    // The default sampler emits everything; the user bean drops this line.
                    writer.snapshots.size shouldBe 0
                }
        }
    }
})
