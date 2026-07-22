# canonical-log-kafka

Kafka integration, framework-agnostic. `kafka-clients` is `compileOnly` (only stable
2.x/3.x types are touched) — your own Kafka version wins at runtime.

## Consumer: one line per record

`KafkaRecordWorkUnitAdapter` is a `WorkUnitAdapter<ConsumerRecord<*, *>>` — the
[message-consumer recipe](../docs/recipes/message-consumers.md)'s adapter, pre-built for
Kafka. The poll loop stays yours:

```kotlin
val adapter = KafkaRecordWorkUnitAdapter()
while (running) {
    for (record in consumer.poll(timeout)) {
        withCanonicalLogBlocking(adapter, record, writer::write) {
            handle(record) // CanonicalLog.put / markFailed as usual
        }
    }
}
```

Each line carries `work_unit_kind=kafka_message` plus `messaging_system=kafka`,
`messaging_destination_name`, `messaging_kafka_partition`, `messaging_kafka_offset`,
`messaging_message_id` (when resolvable — inject a `messageId` lambda for your key/header
scheme), `messaging_process_duration_ms`, and the standard outcome fields
([docs/fields.md](../docs/fields.md)). Frameworks that hide the `ConsumerRecord`
(Spring Kafka listeners etc.) can still open the unit around the listener method — see the
recipe's split-callback section.

## Producer: aggregates on the sending work unit

```kotlin
val producer: Producer<K, V> = KafkaProducer<K, V>(props).withCanonicalLogging()
```

A delegating decorator (deliberately not a `ProducerInterceptor`, which has no construction
seam and acks on unbound IO threads). It captures the work unit on the calling thread at
`send` and writes `kafka_produce_count` at submit, `kafka_produce_duration_ms_total` /
`kafka_produce_error_count` from the ack callback against the captured unit. With no unit
bound it's pure delegation, zero fields.

**Acks that land after the line is emitted are cut off** (same snapshot rule as OkHttp
`enqueue()`): a unit that must account for its sends should `flush()` or block on the
returned `Future` before returning.
