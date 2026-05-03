package io.canonlog.sample

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import net.logstash.logback.marker.MapEntriesAppendingMarker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import java.util.concurrent.TimeUnit

private val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()

private fun lastCanonicalSnapshot(appender: ListAppender<ILoggingEvent>): Map<String, Any>? {
    val event = appender.list.lastOrNull { it.loggerName == "canonical" } ?: return null
    val args: Array<out Any?> = event.argumentArray ?: emptyArray()
    val markers: List<Any> = (event.markerList ?: emptyList<Marker>()) + args.filterNotNull()
    return markers.filterIsInstance<MapEntriesAppendingMarker>()
        .map { marker ->
            val mapField = generateSequence<Class<*>>(marker::class.java) { it.superclass }
                .firstNotNullOfOrNull { cls ->
                    cls.declaredFields.firstOrNull { f -> Map::class.java.isAssignableFrom(f.type) }
                } ?: error("no map field on ${marker::class.java}")
            mapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            mapField.get(marker) as Map<String, Any>
        }
        .fold(emptyMap<String, Any>()) { acc, m -> acc + m }
}

private fun arbAction(maxDepth: Int): Arb<Action> {
    val keys = Arb.element("a", "b", "c", "d")
    val amounts = Arb.long(1L..100L)
    val dispatchers = Arb.element(*PlanDispatcher.entries.toTypedArray())

    val leaf: Arb<Action> = Arb.bind(keys, amounts) { k, n -> Action.Increment(k, n) }
    if (maxDepth <= 0) return leaf

    val child = arbAction(maxDepth - 1)
    return Arb.choice(
        leaf,
        Arb.list(child, 0..3).map { Action.Sequential(it) },
        Arb.bind(dispatchers, child) { d, c -> Action.WithSwitch(d, c) },
        child.map { Action.InScope(it) },
        Arb.list(child, 1..3).map { Action.FanOut(it) },
        Arb.bind(dispatchers, child) { d, c -> Action.AsyncAwait(d, c) },
    )
}

private data class BootedApp(val ctx: ConfigurableApplicationContext, val port: Int)

private fun bootApp(virtualThreads: Boolean, label: String): BootedApp {
    val ctx = SpringApplication.run(
        Application::class.java,
        "--server.port=0",
        "--spring.threads.virtual.enabled=$virtualThreads",
        "--spring.datasource.url=jdbc:h2:mem:canonlog-prop-$label;DB_CLOSE_DELAY=-1",
    )
    val port = ctx.environment.getProperty("local.server.port", Int::class.java)!!
    return BootedApp(ctx, port)
}

class FullStackPropertyTest : DescribeSpec({

    var virtual: BootedApp? = null
    var platform: BootedApp? = null
    var appender: ListAppender<ILoggingEvent> = ListAppender()
    val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    beforeSpec {
        PropertyTesting.defaultIterationCount = 100
        virtual = bootApp(virtualThreads = true, label = "virtual")
        platform = bootApp(virtualThreads = false, label = "platform")

        appender = ListAppender<ILoggingEvent>().also { it.start() }
        val canonicalLogger = LoggerFactory.getLogger("canonical") as LogbackLogger
        canonicalLogger.addAppender(appender)
        canonicalLogger.level = Level.INFO
    }

    afterSpec {
        (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
        virtual?.ctx?.close()
        platform?.ctx?.close()
    }

    listOf("virtual" to { virtual!! }, "platform" to { platform!! }).forEach { (modeName, app) ->
        describe("Full-stack property on $modeName threads") {

            it("every increment in a random plan lands in the canonical log line for that request") {
                checkAll(arbAction(maxDepth = 3)) { plan ->
                    appender.list.clear()

                    val body = mapper.writeValueAsString(plan).toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("http://localhost:${app().port}/property/run")
                        .post(body)
                        .build()

                    client.newCall(req).execute().use { resp ->
                        resp.code shouldBe 200
                    }

                    val snap = lastCanonicalSnapshot(appender)!!
                    snap["http_route"] shouldBe "/property/run"
                    snap["url_path"] shouldBe "/property/run"
                    snap["http_response_status_code"] shouldBe 200

                    val expected = collectExpected(plan)
                    expected.forEach { (k, v) -> snap[k] shouldBe v }
                }
            }
        }
    }
})
