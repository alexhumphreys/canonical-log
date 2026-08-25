package io.github.alexhumphreys.canonicallog.sample

import io.github.alexhumphreys.canonicallog.okhttp.spring.OkHttpClientBuilderCustomizer
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import jakarta.annotation.PreDestroy

@SpringBootApplication
@EnableScheduling
class Application {

    private val upstream = MockWebServer()

    /** Per-path attempt counter driving the flaky upstream's first-two-calls-fail behaviour. */
    private val flakyAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Bean
    fun mockUpstream(): MockWebServer {
        upstream.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.target.startsWith("/comments/")) {
                    return MockResponse(code = 200, body = """{"count":7}""")
                }
                // The flaky upstream the resilience demo calls: it 500s the first two
                // times it's asked for a given path, then serves normally. Enough to make
                // a Retry visibly retry and, under load, to trip the breaker.
                if (request.target.startsWith("/flaky/")) {
                    val attempts = flakyAttempts.merge(request.target, 1, Int::plus)!!
                    return if (attempts <= 2) {
                        MockResponse(code = 500, body = "upstream unavailable")
                    } else {
                        MockResponse(code = 200, body = """{"ok":true}""")
                    }
                }
                if (request.target.startsWith("/author/")) {
                    return MockResponse(code = 200, body = """{"name":"Alex"}""")
                }
                return MockResponse(code = 404)
            }
        }
        upstream.start()
        return upstream
    }

    /**
     * Resilience4j registries as plain beans — no `resilience4j-spring-boot3` needed for the
     * demo. `canonical-log-resilience4j-spring-boot-starter` picks up any registry bean in the
     * context and attaches the canonical contributor, so `/posts/{id}/flaky` produces retry and
     * rejection fields on its canonical line without the controller mentioning canonical-log.
     */
    @Bean
    fun retryRegistry(): RetryRegistry = RetryRegistry.of(
        RetryConfig.custom<Any>()
            .maxAttempts(3)
            .waitDuration(java.time.Duration.ofMillis(50))
            .build(),
    )

    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry = CircuitBreakerRegistry.of(
        // Deliberately twitchy so a handful of curl calls actually trips it.
        CircuitBreakerConfig.custom()
            .slidingWindowSize(6)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(java.time.Duration.ofSeconds(10))
            .build(),
    )

    /**
     * Demonstrates the customizer pattern adopters should use. The starter provides
     * a `canonicalOkHttpClientBuilderCustomizer` bean that adds the canonical
     * interceptor; we apply every registered customizer here. Adopters drop this
     * bean into their own `@Configuration` verbatim.
     */
    @Bean
    fun okHttpClient(customizers: List<OkHttpClientBuilderCustomizer>): OkHttpClient {
        val builder = OkHttpClient.Builder()
        customizers.forEach { it.customize(builder) }
        return builder.build()
    }

    @PreDestroy
    fun shutdown() {
        upstream.close()
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
