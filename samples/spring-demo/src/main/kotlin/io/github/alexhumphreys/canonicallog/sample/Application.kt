package io.github.alexhumphreys.canonicallog.sample

import io.github.alexhumphreys.canonicallog.okhttp.spring.OkHttpClientBuilderCustomizer
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

    @Bean
    fun mockUpstream(): MockWebServer {
        upstream.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.target.startsWith("/comments/")) {
                    return MockResponse(code = 200, body = """{"count":7}""")
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
