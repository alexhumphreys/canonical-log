package io.github.alexhumphreys.canonicallog.sqs

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.adapterContract
import io.kotest.core.spec.style.DescribeSpec
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName

/**
 * The shared adapter contract (todo 048) for [SqsMessageWorkUnitAdapter]. Additive to
 * [SqsMessageWorkUnitAdapterTest], which pins the field semantics.
 */
class SqsAdapterContractTest : DescribeSpec({

    describe("SqsMessageWorkUnitAdapter adapter contract") {
        adapterContract(
            name = "sqs adapter",
            adapter = SqsMessageWorkUnitAdapter("https://sqs.eu-west-1.amazonaws.com/123456789012/orders"),
            inputs = mapOf(
                "a typical message" to Message.builder()
                    .messageId("msg-1")
                    .body("hello")
                    .attributes(mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "2"))
                    .build(),
                // The SDK model makes every property optional; a hand-built or stubbed message
                // (a test double, a framework that re-materializes messages) can lack an id.
                "a message with no id" to Message.builder().build(),
                "an unparseable receive count" to Message.builder()
                    .messageId("msg-2")
                    .attributes(mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "many"))
                    .build(),
                "a hostile body" to Message.builder().messageId("msg-3").body("\u0000".repeat(100_000)).build(),
            ),
            expectedFields = mapOf(
                "messaging_system" to String::class,
                "messaging_destination_name" to String::class,
                "messaging_process_duration_ms" to Long::class,
                CanonicalFields.WORK_UNIT_ID to String::class,
                CanonicalFields.WORK_UNIT_KIND to String::class,
            ),
            allowedFields = setOf(
                "messaging_message_id",
                "messaging_sqs_receive_count",
                CanonicalFields.ERROR,
                CanonicalFields.ERROR_CLASS,
                CanonicalFields.ERROR_REASON,
                CanonicalFields.CANCELLED,
                CanonicalFields.CANCEL_REASON,
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
