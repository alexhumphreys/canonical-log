package io.github.alexhumphreys.canonicallog.sqs

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName

/**
 * Pins `SqsMessageWorkUnitAdapter` by driving a `Message.builder()` message (pure data — no
 * LocalStack/containers) through the real `withCanonicalLogBlocking` lifecycle and asserting on
 * the emitted snapshot. Covers the success line, queue-URL normalization, the receive-count
 * attribute prerequisite (present/absent/unparseable), thrown-handler error mapping, the
 * check-before-default `error_reason` pin, and the negatives (no error on success, never a
 * body-derived field).
 */
class SqsMessageWorkUnitAdapterTest : DescribeSpec({

    fun message(
        id: String = "msg-1",
        body: String = "hello",
        attributes: Map<MessageSystemAttributeName, String> = emptyMap(),
    ): Message =
        Message.builder()
            .messageId(id)
            .body(body)
            .attributes(attributes)
            .build()

    // Run a message through the adapter and return the emitted field snapshot. Block exceptions
    // are captured so failure lines are assertable.
    fun run(
        adapter: SqsMessageWorkUnitAdapter,
        msg: Message,
        block: () -> Unit = {},
    ): Map<String, Any> {
        var fields: Map<String, Any> = emptyMap()
        try {
            withCanonicalLogBlocking(adapter, msg, emit = { fields = it.snapshot() }) { block() }
        } catch (_: Exception) {
            // captured on the line; assertions read `fields`
        }
        return fields
    }

    describe("SqsMessageWorkUnitAdapter") {
        it("emits system, destination, message id, and duration on the success path") {
            val fields = run(SqsMessageWorkUnitAdapter("orders"), message(id = "abc-123"))

            fields["messaging_system"] shouldBe "aws_sqs"
            fields["messaging_destination_name"] shouldBe "orders"
            fields["messaging_message_id"] shouldBe "abc-123"
            fields["work_unit_id"] shouldBe "abc-123"
            fields["work_unit_kind"] shouldBe "sqs_message"
            fields shouldContainKey "messaging_process_duration_ms"
            (fields["messaging_process_duration_ms"] is Long) shouldBe true
        }

        it("normalizes a queue URL to the bare queue name (the URL-passthrough bug)") {
            val adapter = SqsMessageWorkUnitAdapter(
                "https://sqs.eu-west-1.amazonaws.com/123456789012/orders",
            )
            val fields = run(adapter, message())

            fields["messaging_destination_name"] shouldBe "orders"
        }

        it("emits the receive count when the ApproximateReceiveCount attribute is present") {
            val fields = run(
                SqsMessageWorkUnitAdapter("orders"),
                message(
                    attributes = mapOf(
                        MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "3",
                    ),
                ),
            )

            fields["messaging_sqs_receive_count"] shouldBe 3L
        }

        it("omits the receive count when the attribute is absent (poll didn't request it)") {
            val fields = run(SqsMessageWorkUnitAdapter("orders"), message())

            fields shouldNotContainKey "messaging_sqs_receive_count"
        }

        it("omits the receive count when the attribute is present but unparseable") {
            val fields = run(
                SqsMessageWorkUnitAdapter("orders"),
                message(
                    attributes = mapOf(
                        MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "not-a-number",
                    ),
                ),
            )

            fields shouldNotContainKey "messaging_sqs_receive_count"
        }

        it("maps a thrown handler to error=true / error_class / error_reason=exception") {
            val fields = run(SqsMessageWorkUnitAdapter("orders"), message()) {
                throw IllegalStateException("boom")
            }

            fields["error"] shouldBe true
            fields["error_class"] shouldBe "java.lang.IllegalStateException"
            fields["error_reason"] shouldBe "exception"
        }

        it("does not clobber a handler-set error_reason (check-before-default pin)") {
            val fields = run(SqsMessageWorkUnitAdapter("orders"), message()) {
                CanonicalLog.markFailed("dead_lettered")
                throw IllegalStateException("boom")
            }

            fields["error"] shouldBe true
            fields["error_class"] shouldBe "java.lang.IllegalStateException"
            fields["error_reason"] shouldBe "dead_lettered"
        }

        it("writes no error field on the success path") {
            val fields = run(SqsMessageWorkUnitAdapter("orders"), message())

            fields shouldNotContainKey "error"
            fields shouldNotContainKey "error_class"
            fields shouldNotContainKey "error_reason"
        }

        it("never captures body-derived fields — payloads are handler territory") {
            val fields = run(
                SqsMessageWorkUnitAdapter("orders"),
                message(body = "{\"secret\":\"do-not-log\"}"),
            )

            fields.values.none { it.toString().contains("do-not-log") } shouldBe true
        }
    }
})
