package io.github.alexhumphreys.canonicallog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val mapper = ObjectMapper()

/** Same adversarial chunk pool as the 028 round-trip property test, driven by [kotlin.random.Random]
 *  instead of an Arb generator (no extra dependency needed for a raw concurrency hammer). */
private val ADVERSARIAL_CHUNKS = listOf(
    "\"", "\\", "\n", "\r", "\t", "\b", "\u000C",
    "\u0000", "\u0001", "\u001F",
    "a", "Z", "0", " ", "é", "ü", "中", "文",
    "😀", "🚀", "/", "{", "}", ":", ",",
)

private fun randomAdversarialString(rnd: Random): String {
    val len = rnd.nextInt(0, 8)
    return (0 until len).joinToString("") { ADVERSARIAL_CHUNKS[rnd.nextInt(ADVERSARIAL_CHUNKS.size)] }
}

private fun randomValue(rnd: Random): Any = when (rnd.nextInt(4)) {
    0 -> randomAdversarialString(rnd)
    1 -> rnd.nextLong()
    2 -> rnd.nextBoolean()
    else -> rnd.nextDouble()
}

/** One line's fields: a unique token (plus an echo, to catch a torn/mixed-token line) and adversarial values. */
private fun buildLineFields(token: String, rnd: Random): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    map["token"] = token
    map["token_echo"] = token
    repeat(5) { i -> map["val_$i"] = randomValue(rnd) }
    return map
}

private fun assertNodeMatches(node: JsonNode, value: Any) {
    when (value) {
        is String -> node.textValue() shouldBe value
        is Long -> node.longValue() shouldBe value
        is Boolean -> node.booleanValue() shouldBe value
        is Double -> node.doubleValue() shouldBe value
        else -> error("unexpected value type: ${value::class}")
    }
}

private class ConcurrentListAppender : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    override fun append(event: ILoggingEvent) {
        events += event
    }
}

private fun attachAppender(): ConcurrentListAppender {
    val appender = ConcurrentListAppender().also { it.start() }
    val logger = LoggerFactory.getLogger("canonical") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.INFO
    return appender
}

private fun detachAppender(appender: ConcurrentListAppender) {
    (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
}

/**
 * Extends 028's single-threaded round-trip property to the concurrent case (todo 039 §1):
 * many threads emitting through **one shared** [JsonCanonicalLineWriter] instance must never
 * produce a torn/interleaved line, must emit exactly one line per call, and every line's fields
 * must round-trip exactly against what that call put in — no sink-level bleed between lines.
 */
@OptIn(DelicateCanonicalLogApi::class)
class JsonCanonicalLineWriterConcurrentTest : DescribeSpec({

    describe("concurrent JsonCanonicalLineWriter: shared instance, custom collector") {

        it("200 threads x 50 lines each: every line parses, single token, exact round-trip") {
            val threadCount = 200
            val linesPerThread = 50
            val collected = ConcurrentLinkedQueue<String>()
            val writer = JsonCanonicalLineWriter(emitLine = { collected.add(it) })
            val expected = ConcurrentHashMap<String, Map<String, Any>>()
            val pool = Executors.newFixedThreadPool(32)
            try {
                val futures = (0 until threadCount).map { t ->
                    pool.submit {
                        val rnd = Random(t * 104729L + 17)
                        repeat(linesPerThread) { i ->
                            val token = "t$t-l$i-${UUID.randomUUID()}"
                            val ctx = CanonicalLogContext(WorkUnit(token, "scheduled_job", Instant.now()))
                            val fields = buildLineFields(token, rnd)
                            fields.forEach { (k, v) -> ctx.put(k, v) }
                            writer.write(ctx)
                            expected[token] = fields
                        }
                    }
                }
                futures.forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
            }

            val lines = collected.toList()
            lines.size shouldBe threadCount * linesPerThread

            val tokensSeen = mutableSetOf<String>()
            lines.forEach { json ->
                val node = mapper.readTree(json) // throws if the line is torn/unparseable
                val token = node.get("token").textValue()
                tokensSeen.add(token) shouldBe true // uniqueness: a bled-in duplicate token fails this
                node.get("token_echo").textValue() shouldBe token // mixed-token line detector

                val expectedFields = expected[token] ?: error("unexpected/unknown token $token")
                expectedFields.forEach { (k, v) -> assertNodeMatches(node.get(k), v) }
            }
            tokensSeen.size shouldBe threadCount * linesPerThread
        }
    }

    describe("concurrent JsonCanonicalLineWriter: shared instance, real logback logger + ListAppender") {

        it("200 threads x 50 lines each through the default slf4j transport: every event parses, single token, exact round-trip") {
            val threadCount = 200
            val linesPerThread = 50
            val appender = attachAppender()
            val writer = JsonCanonicalLineWriter()
            val expected = ConcurrentHashMap<String, Map<String, Any>>()
            val pool = Executors.newFixedThreadPool(32)
            try {
                val futures = (0 until threadCount).map { t ->
                    pool.submit {
                        val rnd = Random(t * 15485863L + 29)
                        repeat(linesPerThread) { i ->
                            val token = "u$t-l$i-${UUID.randomUUID()}"
                            val ctx = CanonicalLogContext(WorkUnit(token, "scheduled_job", Instant.now()))
                            val fields = buildLineFields(token, rnd)
                            fields.forEach { (k, v) -> ctx.put(k, v) }
                            writer.write(ctx)
                            expected[token] = fields
                        }
                    }
                }
                futures.forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
                detachAppender(appender)
            }

            val events = appender.events.filter { it.loggerName == "canonical" }
            events.size shouldBe threadCount * linesPerThread

            val tokensSeen = mutableSetOf<String>()
            events.forEach { event ->
                val node = mapper.readTree(event.formattedMessage)
                val token = node.get("token").textValue()
                tokensSeen.add(token) shouldBe true
                node.get("token_echo").textValue() shouldBe token

                val expectedFields = expected[token] ?: error("unexpected/unknown token $token")
                expectedFields.forEach { (k, v) -> assertNodeMatches(node.get(k), v) }
            }
            tokensSeen.size shouldBe threadCount * linesPerThread
        }
    }

    describe("snapshot-vs-late-increment race (todo 039 §2)") {

        it("the emitted counter is a consistent prefix between the pre-write floor and the post-hammer total") {
            val ctx = CanonicalLogContext(WorkUnit("wu-race", "scheduled_job", Instant.now()))
            ctx.increment("count", 10L)
            val floor = ctx.snapshot()["count"] as Long

            val releaseEmit = CountDownLatch(1)
            var captured: String? = null
            // Slow the writer down so increments race the in-flight write; the snapshot cutoff
            // itself already happened by the time this lambda runs (write() computes the JSON
            // eagerly before invoking emitLine), so this pins that the *value baked into the
            // string* is a clean value from somewhere in [floor, final] -- never a torn read.
            val writer = JsonCanonicalLineWriter(emitLine = { json ->
                releaseEmit.await(10, TimeUnit.SECONDS)
                captured = json
            })

            val pool = Executors.newFixedThreadPool(17)
            try {
                val writeFuture = pool.submit { writer.write(ctx) }
                val hammerFutures = (0 until 16).map {
                    pool.submit {
                        repeat(500) { ctx.increment("count") }
                    }
                }
                hammerFutures.forEach { it.get(30, TimeUnit.SECONDS) }
                val final = ctx.snapshot()["count"] as Long

                releaseEmit.countDown()
                writeFuture.get(30, TimeUnit.SECONDS)

                val node = mapper.readTree(captured)
                val emittedCount = node.get("count").longValue()
                (emittedCount in floor..final) shouldBe true
            } finally {
                pool.shutdown()
            }
        }
    }
})
