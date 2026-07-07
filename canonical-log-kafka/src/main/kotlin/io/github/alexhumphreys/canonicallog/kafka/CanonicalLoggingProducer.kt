package io.github.alexhumphreys.canonicallog.kafka

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import java.util.concurrent.Future

/**
 * Wrap a Kafka [Producer] so every `send` issued *inside an active canonical work unit*
 * contributes producer-side aggregates to that unit's line:
 * [CanonicalFields.KAFKA_PRODUCE_COUNT], [CanonicalFields.KAFKA_PRODUCE_DURATION_MS_TOTAL]
 * (submit → ack wall-clock), and [CanonicalFields.KAFKA_PRODUCE_ERROR_COUNT] on a failed ack.
 *
 * ```
 * val producer: Producer<String, String> = KafkaProducer<String, String>(props).withCanonicalLogging()
 * // ... inside a work unit:
 * producer.send(ProducerRecord("orders", key, value))
 * ```
 *
 * **A decorator, capture-at-submit — not a `ProducerInterceptor`.** `ProducerInterceptor` was
 * rejected: interceptors are instantiated *reflectively from producer config*, so there is no
 * construction-time seam to resolve the ambient work unit through; and `onAcknowledgement`
 * runs on the producer's IO threads, which carry no canonical binding, so it can't find the
 * originating unit either. This mirrors the OkHttp `enqueue()` request-tag pattern instead:
 * the work unit is captured on the **calling thread** at [send] time (where the binding is
 * live) and every contribution is made against that *captured* context, not whatever the
 * ack-callback thread happens to be bound to.
 *
 * **Zero overhead outside a work unit.** No context bound at submit → pure delegation, no
 * fields, nothing beyond a null check. The adopter's own callback is always invoked, work unit
 * or not. Contributions go through `increment` only (never `put`), honouring the type-conflict
 * rules — `increment` never throws, so telemetry can't fail a send.
 *
 * **Ack cutoff (snapshot-at-emit).** The producer fields land as the ack arrives; the count is
 * recorded synchronously at submit, but the duration (and any error count) only when the ack
 * callback fires. An ack that lands *after* the work unit has emitted its line is silently
 * dropped from that line — the same snapshot-at-emit cutoff as OkHttp `enqueue()` and orphaned
 * coroutine launches. A work unit that must account for its sends should `flush()` / block on
 * the returned `Future` before returning, so the acks are in before emit.
 */
public fun <K, V> Producer<K, V>.withCanonicalLogging(): Producer<K, V> =
    CanonicalLoggingProducer(this)

private class CanonicalLoggingProducer<K, V>(
    private val delegate: Producer<K, V>,
) : Producer<K, V> by delegate {

    override fun send(record: ProducerRecord<K, V>): Future<RecordMetadata> = send(record, null)

    override fun send(record: ProducerRecord<K, V>, callback: Callback?): Future<RecordMetadata> {
        // Capture the work unit on the CALLING thread — the ack callback runs on producer IO
        // threads with no binding, so resolving it there would find nothing.
        val ctx: CanonicalLogContext = currentCanonicalContext()
            ?: return delegate.send(record, callback) // no work unit → pure delegation.

        val startNs = System.nanoTime()
        // Count at submit so a send that never acks before emit still shows up.
        ctx.increment(CanonicalFields.KAFKA_PRODUCE_COUNT)

        val wrapped = Callback { metadata, exception ->
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            // Against the CAPTURED context, not the callback thread's binding.
            ctx.increment(CanonicalFields.KAFKA_PRODUCE_DURATION_MS_TOTAL, durationMs)
            if (exception != null) {
                ctx.increment(CanonicalFields.KAFKA_PRODUCE_ERROR_COUNT)
            }
            // The adopter's own callback must still run in all cases.
            callback?.onCompletion(metadata, exception)
        }
        return delegate.send(record, wrapped)
    }
}
