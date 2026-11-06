package io.github.alexhumphreys.canonicallog.kafka

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.adapterContract
import io.github.alexhumphreys.canonicallog.test.contributorContract
import io.github.alexhumphreys.canonicallog.test.failingScenario
import io.github.alexhumphreys.canonicallog.test.scenario
import io.kotest.core.spec.style.DescribeSpec
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Optional

/**
 * The shared contracts (todo 048) for both halves of the module: the producer decorator is a
 * *contributor* (it writes into the unit bound at `send`), the consumer adapter an *adapter* (it
 * owns the unit's edges). Additive to [CanonicalLoggingProducerTest] / [KafkaRecordWorkUnitAdapterTest].
 */
class KafkaContractTest : DescribeSpec({

    fun mock(autoComplete: Boolean = true) =
        MockProducer(autoComplete, StringSerializer(), StringSerializer())

    // Explicit partition 0 skips the partitioner (empty MockProducer cluster).
    fun record(key: String? = "key", value: String? = "value") = ProducerRecord("orders", 0, key, value)

    val ackedFields = mapOf(
        CanonicalFields.KAFKA_PRODUCE_COUNT to Long::class,
        CanonicalFields.KAFKA_PRODUCE_DURATION_MS_TOTAL to Long::class,
    )

    describe("producer decorator contributor contract") {
        contributorContract(
            name = "kafka producer decorator",
            operation = { mock().withCanonicalLogging().send(record()) },
            expectedFields = ackedFields,
            allowedFieldPrefixes = setOf("kafka_produce_"),
            // A synchronous send failure is the producer's own exception and must pass through.
            failure = failingScenario<KafkaException>(mapOf(CanonicalFields.KAFKA_PRODUCE_COUNT to Long::class)) {
                val underlying = mock()
                underlying.sendException = KafkaException("buffer exhausted")
                underlying.withCanonicalLogging().send(record())
            },
            adversarial = mapOf(
                "a failed acknowledgement" to scenario(
                    ackedFields + (CanonicalFields.KAFKA_PRODUCE_ERROR_COUNT to Long::class),
                ) {
                    val underlying = mock(autoComplete = false)
                    underlying.withCanonicalLogging().send(record())
                    underlying.errorNext(KafkaException("broker down"))
                },
                "a tombstone with a null key" to scenario(ackedFields) {
                    mock().withCanonicalLogging().send(record(key = null, value = null))
                },
                "an adopter callback alongside" to scenario(ackedFields) {
                    mock().withCanonicalLogging().send(record()) { _, _ -> }
                },
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }

    fun consumerRecord(
        topic: String = "orders",
        key: String? = "key",
        value: String? = "value",
        messageId: String? = "msg-7",
        offset: Long = 42L,
    ): ConsumerRecord<String?, String?> {
        val headers = RecordHeaders()
        messageId?.let { headers.add("message-id", it.encodeToByteArray()) }
        return ConsumerRecord(
            topic, 2, offset, 0L, TimestampType.CREATE_TIME, -1, -1, key, value, headers, Optional.empty(),
        )
    }

    describe("consumer adapter contract") {
        adapterContract(
            name = "kafka record adapter",
            // The KDoc's documented header shape — the lambda adopters actually write.
            adapter = KafkaRecordWorkUnitAdapter { it.headers().lastHeader("message-id")?.value()?.decodeToString() },
            inputs = mapOf(
                "a typical record" to consumerRecord(),
                "a record without a message-id header" to consumerRecord(messageId = null),
                "a tombstone with null key and value" to consumerRecord(key = null, value = null),
                "a hostile topic name and an unset offset" to consumerRecord(topic = "a b\n\"c\"=d", offset = -1L),
            ),
            expectedFields = mapOf(
                "messaging_system" to String::class,
                "messaging_destination_name" to String::class,
                "messaging_kafka_partition" to Long::class,
                "messaging_kafka_offset" to Long::class,
                "messaging_process_duration_ms" to Long::class,
                CanonicalFields.WORK_UNIT_ID to String::class,
                CanonicalFields.WORK_UNIT_KIND to String::class,
            ),
            allowedFields = setOf(
                "messaging_message_id",
                CanonicalFields.ERROR,
                CanonicalFields.ERROR_CLASS,
                CanonicalFields.ERROR_REASON,
                CanonicalFields.CANCELLED,
                CanonicalFields.CANCEL_REASON,
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
