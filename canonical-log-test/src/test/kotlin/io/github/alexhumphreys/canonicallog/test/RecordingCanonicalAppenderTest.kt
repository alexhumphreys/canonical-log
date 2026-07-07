package io.github.alexhumphreys.canonicallog.test

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Self-test for [RecordingCanonicalAppender]. Emits on the `"canonical"` logger from *other*
 * threads (mirroring a producer thread with no happens-before to the test thread) and pins the
 * three invariants: await-not-read-once, snapshot-before-iterate, and exactly-one matching.
 */
class RecordingCanonicalAppenderTest : DescribeSpec({

    val canonical = LoggerFactory.getLogger(RecordingCanonicalAppender.CANONICAL_LOGGER_NAME)

    fun emit(message: String, fields: Map<String, String> = emptyMap()) {
        fields.forEach { (k, v) -> MDC.put(k, v) }
        try {
            canonical.info(message)
        } finally {
            fields.keys.forEach(MDC::remove)
        }
    }

    describe("awaitLine") {
        it("finds a line emitted from another thread after a delay") {
            RecordingCanonicalAppender.attach().use { appender ->
                thread {
                    Thread.sleep(200)
                    emit("late", mapOf("url_path" to "/late"))
                }
                val fields = appender.awaitLine { it.mdcPropertyMap["url_path"] == "/late" }
                fields["url_path"] shouldBe "/late"
            }
        }

        it("does not throw when appends hammer the list concurrently during the poll") {
            RecordingCanonicalAppender.attach().use { appender ->
                val stop = AtomicBoolean(false)
                val hammer = thread {
                    while (!stop.get()) {
                        emit("noise")
                    }
                }
                try {
                    // The target line arrives late, so awaitLine keeps snapshotting while the
                    // hammer thread mutates the backing list on every iteration.
                    thread {
                        Thread.sleep(150)
                        emit("target", mapOf("marker" to "hit"))
                    }
                    val fields = appender.awaitLine { it.mdcPropertyMap["marker"] == "hit" }
                    fields["marker"] shouldBe "hit"
                } finally {
                    stop.set(true)
                    hammer.join()
                }
            }
        }

        it("fails the exactly-one check when two lines match the same predicate") {
            RecordingCanonicalAppender.attach().use { appender ->
                emit("first", mapOf("dup" to "yes"))
                emit("second", mapOf("dup" to "yes"))
                shouldThrow<IllegalStateException> {
                    appender.awaitLine { it.mdcPropertyMap["dup"] == "yes" }
                }
            }
        }

        it("throws on timeout when no line matches") {
            RecordingCanonicalAppender.attach().use { appender ->
                shouldThrow<IllegalStateException> {
                    appender.awaitLine(timeoutMs = 100) { it.mdcPropertyMap["nope"] == "x" }
                }
            }
        }
    }

    describe("assertNoLine") {
        it("passes when nothing matches within the window") {
            RecordingCanonicalAppender.attach().use { appender ->
                emit("unrelated", mapOf("url_path" to "/other"))
                appender.assertNoLine(withinMs = 150) { it.mdcPropertyMap["url_path"] == "/excluded" }
            }
        }

        it("fails when a matching line appears within the window") {
            RecordingCanonicalAppender.attach().use { appender ->
                thread {
                    Thread.sleep(50)
                    emit("boom", mapOf("url_path" to "/excluded"))
                }
                shouldThrow<IllegalStateException> {
                    appender.assertNoLine(withinMs = 1_000) { it.mdcPropertyMap["url_path"] == "/excluded" }
                }
            }
        }
    }
})
