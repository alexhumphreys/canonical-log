package io.github.alexhumphreys.canonicallog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import org.slf4j.LoggerFactory
import java.time.Instant

private val mapper = ObjectMapper()

private enum class Color { RED, GREEN }

/** A value whose `toString()` always throws — exercises the per-entry catch. */
private class Exploding {
    override fun toString(): String = throw RuntimeException("boom")
}

private class ListAppender : AppenderBase<ILoggingEvent>() {
    val events = mutableListOf<ILoggingEvent>()
    override fun append(event: ILoggingEvent) {
        events += event
    }
}

private fun attachAppender(): ListAppender {
    val appender = ListAppender().also { it.start() }
    val logger = LoggerFactory.getLogger("canonical") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.INFO
    return appender
}

private fun detachAppender(appender: ListAppender) {
    (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
}

/** Assert a parsed JSON node faithfully represents the original typed value. */
private fun assertNodeMatches(node: JsonNode, value: Any) {
    when (value) {
        is String -> {
            node.isTextual shouldBe true
            node.textValue() shouldBe value
        }
        is Long -> {
            node.isNumber shouldBe true
            node.longValue() shouldBe value
        }
        is Int -> {
            node.isNumber shouldBe true
            node.intValue() shouldBe value
        }
        is Boolean -> {
            node.isBoolean shouldBe true
            node.booleanValue() shouldBe value
        }
        is Double -> {
            node.isNumber shouldBe true
            node.doubleValue() shouldBe value
        }
        else -> error("unexpected value type: ${value::class}")
    }
}

@OptIn(DelicateCanonicalLogApi::class)
class JsonCanonicalLineWriterTest : DescribeSpec({

    beforeSpec { PropertyTesting.defaultIterationCount = 200 }

    describe("canonicalLineJson round-trip against a real JSON parser") {

        it("survives a random-map round-trip: serialize -> parse -> compare") {
            // Chunks cover the escaping-critical cases: quotes, backslashes, every named
            // control escape, other control chars (\u00XX path), multi-byte and emoji
            // (valid surrogate pairs), plus structural JSON punctuation.
            val chunks = Arb.of(
                "\"", "\\", "\n", "\r", "\t", "\b", "\u000C",
                "\u0000", "\u0001", "\u001F",
                "a", "Z", "0", " ", "é", "ü", "中", "文",
                "😀", "🚀", "/", "{", "}", ":", ",",
            )
            val strings = Arb.list(chunks, 0..12).let { arb -> arb.map { it.joinToString("") } }
            val values: Arb<Any> = Arb.choice(
                strings.map { it as Any },
                Arb.long().map { it as Any },
                Arb.int().map { it as Any },
                Arb.boolean().map { it as Any },
                Arb.double().filter { it.isFinite() }.map { it as Any },
            )
            val maps = Arb.map(strings, values, maxSize = 8)

            checkAll(maps) { m ->
                val json = canonicalLineJson(m)
                val root = mapper.readTree(json)
                root.isObject shouldBe true
                root.fieldNames().asSequence().toSet() shouldBe m.keys
                m.forEach { (k, v) -> assertNodeMatches(root.get(k), v) }
            }
        }
    }

    describe("canonicalLineJson determinism and key ordering") {

        it("is deterministic and sorts keys lexicographically") {
            val m = mapOf<String, Any>("zebra" to 1L, "apple" to 2L, "mango" to 3L)
            canonicalLineJson(m) shouldBe canonicalLineJson(m)
            canonicalLineJson(m) shouldBe """{"apple":2,"mango":3,"zebra":1}"""
        }
    }

    describe("canonicalLineJson type discipline") {

        it("keeps Long unquoted") {
            canonicalLineJson(mapOf("db_query_count" to 3L)) shouldBe """{"db_query_count":3}"""
        }

        it("keeps Int/Short/Byte unquoted") {
            canonicalLineJson(mapOf("i" to 7, "s" to 5.toShort(), "b" to 2.toByte())) shouldBe
                """{"b":2,"i":7,"s":5}"""
        }

        it("renders Boolean bare") {
            canonicalLineJson(mapOf("error" to true, "degraded" to false)) shouldBe
                """{"degraded":false,"error":true}"""
        }

        it("renders finite Double/Float as numbers") {
            canonicalLineJson(mapOf("d" to 1.5)) shouldBe """{"d":1.5}"""
        }

        it("renders NaN and infinities as strings, never throwing") {
            canonicalLineJson(mapOf("d" to Double.NaN)) shouldBe """{"d":"NaN"}"""
            canonicalLineJson(mapOf("d" to Double.POSITIVE_INFINITY)) shouldBe """{"d":"Infinity"}"""
            canonicalLineJson(mapOf("d" to Double.NEGATIVE_INFINITY)) shouldBe """{"d":"-Infinity"}"""
        }

        it("renders an enum as its name string") {
            canonicalLineJson(mapOf("color" to Color.GREEN)) shouldBe """{"color":"GREEN"}"""
        }

        it("renders a list's toString as an escaped string (the honest smell)") {
            canonicalLineJson(mapOf("tags" to listOf(1, 2))) shouldBe """{"tags":"[1, 2]"}"""
        }

        it("escapes named control chars and \\uXXXX for the rest, in keys and values") {
            canonicalLineJson(mapOf("k" to "\n\t\u0001")) shouldBe """{"k":"\n\t\u0001"}"""
            canonicalLineJson(mapOf("a\"\\b" to "x")) shouldBe """{"a\"\\b":"x"}"""
        }

        it("renders a throwing toString() as a placeholder, never propagating") {
            val json = canonicalLineJson(mapOf("bad" to Exploding()))
            json shouldStartWith """{"bad":"<serialization_failed:"""
            json shouldContain "Exploding"
            json shouldEndWith """>"}"""
            // still valid JSON
            mapper.readTree(json).get("bad").isTextual shouldBe true
        }

        it("skips a null value defensively (put drops nulls, but guard anyway)") {
            @Suppress("UNCHECKED_CAST")
            val withNull = mapOf("keep" to 1L, "drop" to null) as Map<String, Any>
            canonicalLineJson(withNull) shouldBe """{"keep":1}"""
        }
    }

    describe("JsonCanonicalLineWriter") {

        it("passes the exact canonicalLineJson string to a custom emitLine") {
            val ctx = CanonicalLogContext(WorkUnit("wu-1", "scheduled_job", Instant.now()))
            ctx.put("job_name", "ReportingJob.run")
            ctx.increment("db_query_count", 3L)

            var captured: String? = null
            JsonCanonicalLineWriter(emitLine = { captured = it }).write(ctx)

            val snapshot = ctx.snapshot()
            val expected = canonicalLineJson(snapshot + ("message" to canonicalLineMessage(snapshot)))
            captured shouldBe expected
        }

        it("folds the human summary under a 'message' key when the snapshot has none") {
            val ctx = CanonicalLogContext(WorkUnit("wu-2", "scheduled_job", Instant.now()))
            ctx.put("job_name", "ReportingJob.run")

            var captured: String? = null
            JsonCanonicalLineWriter(emitLine = { captured = it }).write(ctx)

            val node = mapper.readTree(captured)
            node.get("message").textValue() shouldBe canonicalLineMessage(ctx.snapshot())
        }

        it("does not clobber a handler-owned 'message' field") {
            val ctx = CanonicalLogContext(WorkUnit("wu-3", "scheduled_job", Instant.now()))
            ctx.put("message", "handler-owned summary")

            var captured: String? = null
            JsonCanonicalLineWriter(emitLine = { captured = it }).write(ctx)

            mapper.readTree(captured).get("message").textValue() shouldBe "handler-owned summary"
        }

        it("default transport emits one event on 'canonical' whose message parses as the full typed object") {
            val appender = attachAppender()
            try {
                val ctx = CanonicalLogContext(WorkUnit("wu-4", "scheduled_job", Instant.now()))
                ctx.put("job_name", "ReportingJob.run")
                ctx.increment("db_query_count", 3L)

                JsonCanonicalLineWriter().write(ctx)

                appender.events.size shouldBe 1
                val node = mapper.readTree(appender.events.single().formattedMessage)
                node.get("db_query_count").longValue() shouldBe 3L
                node.get("job_name").textValue() shouldBe "ReportingJob.run"
                node.get("work_unit_id") shouldBe null // WorkUnit identity isn't auto-put into the context
                node.get("message").isTextual shouldBe true
            } finally {
                detachAppender(appender)
            }
        }
    }
})
