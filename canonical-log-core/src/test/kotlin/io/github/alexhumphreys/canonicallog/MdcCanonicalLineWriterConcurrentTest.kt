package io.github.alexhumphreys.canonicallog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val MDC_ADVERSARIAL_CHUNKS = listOf(
    "\"", "\\", "\n", "\r", "\t", "\u0000", "\u001F",
    "a", "Z", "0", " ", "é", "ü", "中", "文", "😀", "🚀", "/", "{", "}", ":", ",",
)

private fun randomAdversarialString(rnd: Random): String {
    val len = rnd.nextInt(0, 8)
    return (0 until len).joinToString("") { MDC_ADVERSARIAL_CHUNKS[rnd.nextInt(MDC_ADVERSARIAL_CHUNKS.size)] }
}

private fun randomValue(rnd: Random): Any = when (rnd.nextInt(4)) {
    0 -> randomAdversarialString(rnd)
    1 -> rnd.nextLong()
    2 -> rnd.nextBoolean()
    else -> rnd.nextDouble()
}

/** One line's fields: a unique token (plus an echo, to catch a torn/mixed-token event) and adversarial values. */
private fun buildLineFields(token: String, rnd: Random): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    map["token"] = token
    map["token_echo"] = token
    repeat(5) { i -> map["val_$i"] = randomValue(rnd) }
    return map
}

/** Forces each event's MDC snapshot at append time, before MdcCanonicalLineWriter's `finally` unwinds it.
 *  A plain ListAppender reads getMDCPropertyMap() lazily and would see an already-restored MDC. */
private class MdcCapturingConcurrentAppender : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    override fun append(event: ILoggingEvent) {
        event.mdcPropertyMap
        events += event
    }
}

private fun attachAppender(): MdcCapturingConcurrentAppender {
    val appender = MdcCapturingConcurrentAppender().also { it.start() }
    val logger = LoggerFactory.getLogger("canonical") as LogbackLogger
    logger.addAppender(appender)
    logger.level = Level.INFO
    return appender
}

private fun detachAppender(appender: MdcCapturingConcurrentAppender) {
    (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
}

/**
 * Concurrent counterpart to [MdcCanonicalLineWriterTest] (todo 039 §1). MDC is per-thread, so
 * the risk here isn't cross-thread bleed in the MDC map itself -- it's whether the
 * flatten-then-restore window in a **shared writer instance**, hammered from 200 threads at
 * once, ever produces an event whose captured MDC snapshot doesn't match exactly what that
 * thread put into its own context (a torn flatten, or a restore racing the next write on a
 * reused pool thread).
 */
@OptIn(DelicateCanonicalLogApi::class)
class MdcCanonicalLineWriterConcurrentTest : DescribeSpec({

    describe("concurrent MdcCanonicalLineWriter: shared instance across 200 threads") {

        it("200 threads x 50 lines each: every event's captured MDC round-trips exactly, single token, no cross-event bleed") {
            val threadCount = 200
            val linesPerThread = 50
            val appender = attachAppender()
            val writer = MdcCanonicalLineWriter()
            val expected = ConcurrentHashMap<String, Map<String, String>>()
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
                            expected[token] = fields.mapValues { it.value.toString() }
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
                val mdc = event.mdcPropertyMap
                val token = mdc["token"] ?: error("no token field captured")
                tokensSeen.add(token) shouldBe true
                mdc["token_echo"] shouldBe token

                val expectedFields = expected[token] ?: error("unexpected/unknown token $token")
                mdc shouldContainAll expectedFields
            }
            tokensSeen.size shouldBe threadCount * linesPerThread
        }
    }
})
