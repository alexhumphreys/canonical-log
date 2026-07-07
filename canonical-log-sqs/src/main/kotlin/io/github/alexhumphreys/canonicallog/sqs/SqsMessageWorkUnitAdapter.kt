package io.github.alexhumphreys.canonicallog.sqs

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.time.Instant

/**
 * [WorkUnitAdapter] for AWS SQS message consumers, capturing the mechanically-uniform messaging
 * fields for every message consumed off a queue. This is the shipped, SQS-specific concretization
 * of the broker-agnostic poll-loop recipe in `docs/recipes/message-consumers.md` — pair it with
 * `withCanonicalLogBlocking` (closure loop) or `openCanonicalWorkUnit` (split-callback listener).
 *
 * **One instance per queue/worker.** The [queueName] is a constructor argument because an SQS
 * [Message] does not carry the queue it came from — only the poller knows what it polled. Pass
 * either a bare queue name (`orders`) or a full queue URL
 * (`https://sqs.eu-west-1.amazonaws.com/123456789012/orders`); a URL is normalized to its last
 * path segment so `messaging_destination_name` is always the bare queue name.
 *
 * Scope discipline: this adapter captures only what is mechanically uniform across every consume
 * (system, destination, message id, receive count, duration, lifecycle outcome). It never reads
 * the message body or user attributes — payload-derived fields (`order_id`, `retry_reason`) are
 * handler territory via `CanonicalLog.put`, the same contributor/handler split the HTTP and
 * scheduled-job adapters follow.
 *
 * Field names are OpenTelemetry messaging-semconv strings (dot → underscore for Loki), written as
 * **string literals** on purpose: there is no messaging contributor in core, so there are no
 * `CanonicalFields` constants for them (see the field-constants gotcha in `docs/CLAUDE.md`).
 * `canonical-log-kafka` defines the same literals independently — they are kept aligned on the wire.
 */
public class SqsMessageWorkUnitAdapter(
    queueName: String,
) : WorkUnitAdapter<Message> {

    /** Normalized to the bare queue name — a queue URL's last path segment, or the value as-is. */
    private val queueName: String = queueName.substringAfterLast('/')

    override fun describe(input: Message): WorkUnit =
        // SQS always assigns a messageId, so identity needs no fallback lambda.
        WorkUnit(id = input.messageId(), kind = "sqs_message", startedAt = Instant.now())

    override fun enrich(ctx: CanonicalLogContext, input: Message, outcome: Outcome) {
        ctx.put("messaging_system", "aws_sqs")
        ctx.put("messaging_destination_name", queueName)
        ctx.put("messaging_message_id", input.messageId())
        ctx.put("messaging_process_duration_ms", outcome.durationMs)

        // ApproximateReceiveCount is only present when the ReceiveMessageRequest asked for the
        // ApproximateReceiveCount system attribute; parse defensively and omit when absent or
        // unparseable so the field never appears as a stringified or bogus value.
        input.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]
            ?.toLongOrNull()
            ?.let { ctx.put("messaging_sqs_receive_count", it) }

        // Identity, mirroring the HTTP / scheduled-job adapters.
        ctx.put("work_unit_id", ctx.workUnit.id)
        ctx.put("work_unit_kind", ctx.workUnit.kind)

        // Lifecycle outcome. A thrown handler is a failure; a business "dead-lettered" outcome the
        // handler set via markFailed must survive (check-before-default on error_reason, the same
        // policy HttpWorkUnitAdapter uses). Cancellation is not an error — cancel_reason only.
        when (outcome) {
            is Outcome.Threw -> {
                ctx.put("error", true)
                ctx.put("error_class", outcome.cause::class.qualifiedName ?: "unknown")
                if (ctx.snapshot()["error_reason"] == null) {
                    ctx.put("error_reason", "exception")
                }
            }
            is Outcome.Cancelled -> {
                ctx.put("cancelled", true)
                if (ctx.snapshot()["cancel_reason"] == null) {
                    ctx.put("cancel_reason", "cancelled")
                }
            }
            is Outcome.Completed -> Unit
        }
    }
}
