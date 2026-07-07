package io.github.alexhumphreys.canonicallog.kafka

import io.github.alexhumphreys.canonicallog.test.captureCanonicalLine
import io.github.alexhumphreys.canonicallog.test.captureCanonicalLineBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit-level tests for the [withCanonicalLogging] producer decorator, driven by
 * [MockProducer] — no broker. Callbacks are completed explicitly (`completeNext` /
 * `errorNext`) so the submit → ack timeline is deterministic.
 */
class CanonicalLoggingProducerTest : DescribeSpec({

    fun mock(autoComplete: Boolean = false) =
        MockProducer<String, String>(autoComplete, StringSerializer(), StringSerializer())

    // Explicit partition 0 skips the partitioner (empty MockProducer cluster).
    fun record() = ProducerRecord("orders", 0, "key", "value")

    describe("inside a work unit") {
        it("lands count + duration on a successful ack, no error count") {
            val underlying = mock()
            val producer = underlying.withCanonicalLogging()
            val line = captureCanonicalLineBlocking { _ ->
                producer.send(record())
                underlying.completeNext() // fires the wrapped callback on this (bound) thread
            }

            line["kafka_produce_count"] shouldBe 1L
            line["kafka_produce_duration_ms_total"].shouldBeInstanceOf<Long>()
            line.fields shouldNotContainKey "kafka_produce_error_count"
        }

        it("increments the error count when the ack fails") {
            val underlying = mock()
            val producer = underlying.withCanonicalLogging()
            val line = captureCanonicalLineBlocking { _ ->
                producer.send(record())
                underlying.errorNext(RuntimeException("broker down"))
            }

            line["kafka_produce_count"] shouldBe 1L
            line["kafka_produce_error_count"] shouldBe 1L
            line["kafka_produce_duration_ms_total"].shouldBeInstanceOf<Long>()
        }

        it("still invokes the adopter's own callback") {
            val called = AtomicBoolean(false)
            val underlying = mock()
            val producer = underlying.withCanonicalLogging()
            captureCanonicalLineBlocking { _ ->
                producer.send(record()) { _, _ -> called.set(true) }
                underlying.completeNext()
            }
            called.get() shouldBe true
        }

        it("drops acks that land after the line is emitted (snapshot-at-emit cutoff)") {
            val underlying = mock()
            val producer = underlying.withCanonicalLogging()
            // Submit inside the unit, but do NOT complete before emit.
            val line = captureCanonicalLineBlocking { _ ->
                producer.send(record())
            }

            // Count is recorded synchronously at submit; duration only arrives with the ack.
            line["kafka_produce_count"] shouldBe 1L
            line.fields shouldNotContainKey "kafka_produce_duration_ms_total"

            // The ack landing after emit must not throw and must not mutate the emitted line.
            underlying.completeNext() shouldBe true
            line.fields shouldNotContainKey "kafka_produce_duration_ms_total"
        }
    }

    describe("no active work unit") {
        it("delegates purely: no fields, no NPE, adopter callback still fires") {
            val called = AtomicBoolean(false)
            val underlying = mock()
            val producer = underlying.withCanonicalLogging()

            val future = producer.send(record()) { _, _ -> called.set(true) }
            underlying.completeNext() shouldBe true

            future.isDone shouldBe true
            called.get() shouldBe true
        }
    }

    describe("concurrency sanity") {
        it("sums counts correctly across a parallel async fan-out in one work unit") {
            val n = 64
            val line = runBlocking {
                captureCanonicalLine {
                    // Each coroutine gets its own MockProducer (MockProducer isn't thread-safe),
                    // but all sends resolve the SAME bound work unit and increment its
                    // ConcurrentHashMap — the actual thing under test.
                    (1..n).map { i ->
                        async(Dispatchers.Default) {
                            val p = MockProducer(true, StringSerializer(), StringSerializer())
                                .withCanonicalLogging()
                            p.send(ProducerRecord("orders", 0, "k", "v$i")).get()
                        }
                    }.awaitAll()
                }
            }

            line["kafka_produce_count"] shouldBe n.toLong()
            line.fields shouldNotContainKey "kafka_produce_error_count"
        }
    }
})
