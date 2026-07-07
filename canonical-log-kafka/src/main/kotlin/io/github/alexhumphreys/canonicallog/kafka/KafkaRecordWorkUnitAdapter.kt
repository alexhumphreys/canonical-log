package io.github.alexhumphreys.canonicallog.kafka

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.Instant
import java.util.UUID

/**
 * [WorkUnitAdapter] for a single Kafka [ConsumerRecord] — the shipped concretisation of the
 * consumer case in `docs/recipes/message-consumers.md`. Open **one work unit per record**
 * (never one per `poll()` batch — a batch is N units, not one), and this adapter captures
 * what's mechanically uniform across every consume: identity in [describe], the messaging
 * fields + duration + outcome in [enrich]. Per-message business values (`order_id`,
 * `retry_attempt`) stay the handler's job via `CanonicalLog.put`.
 *
 * No consumer *loop runner* ships here: the poll loop is the adopter's (see the recipe's
 * closure-shaped loop and split-callback listener). This adapter turns the recipe's
 * hand-rolled `WorkUnitAdapter` into a one-liner.
 *
 * **Message id.** Kafka records have no intrinsic application message id, so [messageId]
 * resolves one — used for the [WorkUnit] id when non-null (else a random UUID) and written as
 * `messaging_message_id` **only when resolvable** (absent, not blank, otherwise). There is no
 * default header sniffing; the common shape reads a producer-set header:
 * ```
 * KafkaRecordWorkUnitAdapter { it.headers().lastHeader("message-id")?.value?.decodeToString() }
 * ```
 *
 * **Fields written** (consumer-side `messaging_*` are OTel messaging-semconv names, dot →
 * underscore for Loki; they are deliberately *string literals*, not `CanonicalFields`
 * constants — see the field-constants gotcha in `docs/CLAUDE.md`):
 * - `messaging_system` = `"kafka"`
 * - `messaging_destination_name` = topic
 * - `messaging_kafka_partition` (Long), `messaging_kafka_offset` (Long) — always present
 * - `messaging_message_id` — only when [messageId] resolves
 * - `messaging_process_duration_ms` (Long) — from the outcome
 * - `work_unit_id` / `work_unit_kind` — identity, mirroring `HttpWorkUnitAdapter`
 *
 * **Outcome mapping** follows the reference adapter's check-before-default precedence
 * ([WorkUnitAdapter.enrich]): a thrown handler → `error=true` / `error_class` / a default
 * `error_reason="exception"` that a handler-set `error_reason` (via `markFailed`) overrides;
 * a cancelled unit → `cancelled=true` + a default `cancel_reason="cancelled"` (never
 * `error=true` — cancellation must not pollute error rates).
 */
public class KafkaRecordWorkUnitAdapter(
    private val messageId: (ConsumerRecord<*, *>) -> String? = { null },
) : WorkUnitAdapter<ConsumerRecord<*, *>> {

    override fun describe(input: ConsumerRecord<*, *>): WorkUnit = WorkUnit(
        id = messageId(input) ?: UUID.randomUUID().toString(),
        kind = "kafka_message",
        startedAt = Instant.now(),
    )

    override fun enrich(ctx: CanonicalLogContext, input: ConsumerRecord<*, *>, outcome: Outcome) {
        ctx.put("work_unit_id", ctx.workUnit.id)
        ctx.put("work_unit_kind", ctx.workUnit.kind)

        ctx.put("messaging_system", "kafka")
        ctx.put("messaging_destination_name", input.topic())
        ctx.put("messaging_kafka_partition", input.partition().toLong())
        ctx.put("messaging_kafka_offset", input.offset())
        // messaging_message_id only when resolvable — omitted (not blank) otherwise, so queries
        // on it don't surface garbage.
        messageId(input)?.let { ctx.put("messaging_message_id", it) }
        ctx.put("messaging_process_duration_ms", outcome.durationMs)

        when (outcome) {
            is Outcome.Threw -> {
                ctx.put("error", true)
                ctx.put("error_class", outcome.cause::class.qualifiedName ?: "unknown")
                // check-before-default: a handler-set error_reason (markFailed / dead-lettering)
                // expresses intent the adapter must not clobber.
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
