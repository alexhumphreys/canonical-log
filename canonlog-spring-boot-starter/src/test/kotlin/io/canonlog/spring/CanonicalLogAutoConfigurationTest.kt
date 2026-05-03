package io.canonlog.spring

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean

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

        it("opts out when canonlog.http.enabled=false") {
            runner.withPropertyValues("canonlog.http.enabled=false").run { ctx ->
                ctx.getBeansOfType(CanonicalLogFilter::class.java).size shouldBe 0
                ctx.getBeansOfType(FilterRegistrationBean::class.java).size shouldBe 0
            }
        }
    }
})
