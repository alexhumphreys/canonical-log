package io.canonlog.okhttp.spring

import io.canonlog.CanonicalLogContext
import io.canonlog.Outcome
import io.canonlog.WorkUnit
import io.canonlog.WorkUnitAdapter
import io.canonlog.okhttp.OkHttpCanonicalInterceptor
import io.canonlog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

@Configuration
private open class ClientConfig {
    @Bean
    open fun okHttpClient(customizers: List<OkHttpClientBuilderCustomizer>): OkHttpClient =
        OkHttpClient.Builder().also { b -> customizers.forEach { it.customize(b) } }.build()
}

private class HeaderInterceptor(private val name: String, private val value: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().addHeader(name, value).build())
}

@Configuration
private open class UserCustomizerConfig {
    @Bean("userCustomizer")
    open fun userCustomizer(): OkHttpClientBuilderCustomizer = OkHttpClientBuilderCustomizer { builder ->
        builder.addInterceptor(HeaderInterceptor("X-Test-User", "yes"))
    }
}

class OkHttpCanonicalAutoConfigurationTest : DescribeSpec({
    val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OkHttpCanonicalAutoConfiguration::class.java))

    describe("OkHttpCanonicalAutoConfiguration") {

        it("registers the interceptor as a bean by default") {
            runner.run { ctx ->
                ctx.getBeansOfType(OkHttpCanonicalInterceptor::class.java).size shouldBe 1
            }
        }

        it("backs off if the user already supplied an interceptor bean") {
            runner.withBean("custom", OkHttpCanonicalInterceptor::class.java).run { ctx ->
                ctx.getBeansOfType(OkHttpCanonicalInterceptor::class.java).size shouldBe 1
                ctx.containsBean("custom") shouldBe true
                ctx.containsBean("okHttpCanonicalInterceptor") shouldBe false
            }
        }

        it("registers the canonical customizer by default") {
            runner.run { ctx ->
                ctx.getBeansOfType(OkHttpClientBuilderCustomizer::class.java).size shouldBe 1
                ctx.containsBean("canonicalOkHttpClientBuilderCustomizer") shouldBe true
            }
        }

        it("a client built via the customizer emits canonical fields when used") {
            // The wiring under test: starter provides a customizer; user config
            // applies it to OkHttpClient.Builder. A real call through the resulting
            // client should produce http_client_request_count = 1.
            val server = MockWebServer().apply {
                enqueue(MockResponse(code = 200, body = "ok"))
                start()
            }
            try {
                runner.withUserConfiguration(ClientConfig::class.java).run { ctx ->
                    val client = ctx.getBean(OkHttpClient::class.java)
                    var snap: Map<String, Any> = emptyMap()
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        client.newCall(Request.Builder().url(server.url("/").toString()).build())
                            .execute().use { it.code shouldBe 200 }
                    }
                    snap["http_client_request_count"] shouldBe 1L
                }
            } finally {
                server.close()
            }
        }

        it("user-supplied customizers compose with the canonical one") {
            // Both the canonical and the user customizer should fire when ClientConfig
            // applies the full list. We observe two effects: the canonical interceptor
            // contributes http_client_request_count=1, and the user interceptor adds a
            // header that we read back from MockWebServer's recorded request.
            val server = MockWebServer().apply {
                enqueue(MockResponse(code = 200, body = "ok"))
                start()
            }
            try {
                runner
                    .withUserConfiguration(ClientConfig::class.java, UserCustomizerConfig::class.java)
                    .run { ctx ->
                        // The canonical bean is still present alongside the user one.
                        ctx.getBeansOfType(OkHttpClientBuilderCustomizer::class.java).keys shouldBe setOf(
                            "canonicalOkHttpClientBuilderCustomizer",
                            "userCustomizer",
                        )

                        val client = ctx.getBean(OkHttpClient::class.java)
                        var snap: Map<String, Any> = emptyMap()
                        withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                            client.newCall(Request.Builder().url(server.url("/").toString()).build())
                                .execute().use { it.code shouldBe 200 }
                        }

                        // Canonical effect: the count contribution landed.
                        snap["http_client_request_count"] shouldBe 1L
                        // User effect: the header reached the server.
                        val recorded = server.takeRequest()
                        recorded.headers["X-Test-User"] shouldBe "yes"
                    }
            } finally {
                server.close()
            }
        }

        it("opts out when canonlog.okhttp.enabled=false") {
            runner.withPropertyValues("canonlog.okhttp.enabled=false").run { ctx ->
                ctx.containsBean("canonicalOkHttpClientBuilderCustomizer") shouldBe false
                ctx.getBeansOfType(OkHttpCanonicalInterceptor::class.java).size shouldBe 0
                ctx.getBeansOfType(OkHttpClientBuilderCustomizer::class.java).size shouldBe 0
            }
        }
    }
})
