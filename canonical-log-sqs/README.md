# canonical-log-sqs

`SqsMessageWorkUnitAdapter` — a `WorkUnitAdapter<Message>` for AWS SQS consumers: one
canonical line per processed message from your own poll loop (the
[message-consumer recipe](../docs/recipes/message-consumers.md), concretized for SQS).
Adapter only: no poll-loop runner, no client wrapper, no outbound instrumentation, and the
library never reads message bodies. The AWS SDK is `compileOnly` — your version wins at
runtime.

```kotlin
val adapter = SqsMessageWorkUnitAdapter(queueUrl) // bare name or full URL, both fine
for (message in sqs.receiveMessage(request).messages()) {
    withCanonicalLogBlocking(adapter, message, writer::write) {
        handle(message)
    }
}
```

The constructor takes the queue name because an SQS `Message` doesn't carry its source
queue — one adapter instance per queue/worker. Full queue URLs are normalized to the queue
name.

Fields written ([docs/fields.md](../docs/fields.md)): `messaging_system=aws_sqs`,
`messaging_destination_name`, `messaging_message_id`, `messaging_process_duration_ms`,
`messaging_sqs_receive_count`, plus the standard outcome mapping.

**`messaging_sqs_receive_count` only appears if you ask SQS for it**: request the
`ApproximateReceiveCount` system attribute on your `ReceiveMessageRequest`, or the attribute
never arrives and the field is omitted.
