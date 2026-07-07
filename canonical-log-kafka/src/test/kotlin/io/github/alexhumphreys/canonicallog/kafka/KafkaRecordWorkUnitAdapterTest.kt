package io.github.alexhumphreys.canonicallog.kafka

import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.test.captureCanonicalLineBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives [KafkaRecordWorkUnitAdapter] through `captureCanonicalLineBlocking`: the adapter's
 * `enrich` is invoked against the harness's live context with a synthesized [Outcome], so the
 * captured snapshot is exactly what the adapter wrote. `describe` is asserted directly.
 */
class KafkaRecordWorkUnitAdapterTest : DescribeSpec({

    fun record(
        topic: String = "orders",
        partition: Int = 2,
        offset: Long = 42L,
    ): ConsumerRecord<String, String> = ConsumerRecord(topic, partition, offset, "key", "value")

    describe("describe") {
        it("uses the messageId lambda for the work-unit id when it resolves") {
            val adapter = KafkaRecordWorkUnitAdapter { "msg-7" }
            val unit = adapter.describe(record())
            unit.id shouldBe "msg-7"
            unit.kind shouldBe "kafka_message"
        }

        it("falls back to a random UUID when messageId is null") {
            val adapter = KafkaRecordWorkUnitAdapter()
            val id = adapter.describe(record()).id
            id.shouldNotBeBlank()
            // Parseable as a UUID (throws IllegalArgumentException otherwise).
            UUID.fromString(id)
        }
    }

    describe("enrich — success") {
        it("writes the messaging_* fields with no error markers") {
            val adapter = KafkaRecordWorkUnitAdapter { "msg-7" }
            val rec = record()
            val line = captureCanonicalLineBlocking { ctx ->
                adapter.enrich(ctx, rec, Outcome.Completed(12))
            }

            line["messaging_system"] shouldBe "kafka"
            line["messaging_destination_name"] shouldBe "orders"
            line["messaging_kafka_partition"] shouldBe 2L
            line["messaging_kafka_offset"] shouldBe 42L
            line["messaging_message_id"] shouldBe "msg-7"
            line["messaging_process_duration_ms"] shouldBe 12L

            line.fields shouldNotContainKey "error"
            line.fields shouldNotContainKey "error_class"
            line.fields shouldNotContainKey "cancelled"
        }

        it("omits messaging_message_id when the id is unresolvable") {
            val adapter = KafkaRecordWorkUnitAdapter() // default { null }
            val rec = record()
            val line = captureCanonicalLineBlocking { ctx ->
                adapter.enrich(ctx, rec, Outcome.Completed(1))
            }
            line.fields shouldNotContainKey "messaging_message_id"
            // topic/partition/offset are always present even without an id.
            line["messaging_destination_name"] shouldBe "orders"
            line["messaging_kafka_partition"] shouldBe 2L
        }
    }

    describe("enrich — thrown") {
        it("maps a thrown outcome to error=true / error_class / default error_reason") {
            val adapter = KafkaRecordWorkUnitAdapter { "msg-9" }
            val rec = record()
            val line = captureCanonicalLineBlocking { ctx ->
                adapter.enrich(ctx, rec, Outcome.Threw(3, IllegalStateException("boom")))
            }
            line["error"] shouldBe true
            line["error_class"] shouldBe "java.lang.IllegalStateException"
            line["error_reason"] shouldBe "exception"
            line.fields shouldNotContainKey "cancelled"
        }

        it("does not clobber a handler-set error_reason (check-before-default)") {
            val adapter = KafkaRecordWorkUnitAdapter { "msg-9" }
            val rec = record()
            val line = captureCanonicalLineBlocking { ctx ->
                ctx.markFailed("dead_lettered") // handler intent, no throw of its own
                adapter.enrich(ctx, rec, Outcome.Threw(3, IllegalStateException("boom")))
            }
            line["error"] shouldBe true
            line["error_reason"] shouldBe "dead_lettered"
            // The lifecycle-level throw still stamps the class.
            line["error_class"] shouldBe "java.lang.IllegalStateException"
        }
    }

    describe("enrich — cancelled") {
        it("maps a cancelled outcome to cancelled=true + default cancel_reason, no error") {
            val adapter = KafkaRecordWorkUnitAdapter { "msg-c" }
            val rec = record()
            val line = captureCanonicalLineBlocking { ctx ->
                adapter.enrich(ctx, rec, Outcome.Cancelled(2, CancellationException("gone")))
            }
            line["cancelled"] shouldBe true
            line["cancel_reason"] shouldBe "cancelled"
            line.fields shouldNotContainKey "error"
            line.fields shouldNotContainKey "error_class"
        }
    }
})
