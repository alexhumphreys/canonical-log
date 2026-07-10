# `canonical-log-sqs` — SQS message work-unit adapter

**Status:** todo · **Modules:** new `canonical-log-sqs`, `canonical-log-core` (one constant),
docs (recipe update)
**Depends on:** 026 (reuses its `MESSAGING_*` constants — do not duplicate them), 024 (the
recipe this aligns with).

## Problem

SQS consumption is poll-based — the AWS SDK exposes no listener/interceptor seam — so there
is nothing to instrument transparently and the 024 recipe's poll-loop shape is already the
right integration. What's missing is the adapter: every SQS adopter currently hand-writes
the same ~20 lines mapping `software.amazon.awssdk.services.sqs.model.Message` onto the
messaging field contract. Ship it, so the recipe's Kafka case (026's
`KafkaRecordWorkUnitAdapter`) has an SQS sibling and one-work-unit-per-message costs one
constructor call.

Scope: adapter only. No poll-loop runner, no client wrapper — the loop is adopter code
(recipe), and outbound `sendMessage` instrumentation is deliberately out of scope until
someone asks (it would follow 026's capture-at-send decorator pattern if ever built).

## Design

### 1. New module

- `settings.gradle.kts`: add `"canonical-log-sqs"`. Package
  `io.github.alexhumphreys.canonicallog.sqs`.
- `build.gradle.kts`: `api(project(":canonical-log-core"))`,
  `compileOnly(libs.awssdk.sqs)` — new catalog entries (`awssdk = "<latest 2.x>"`,
  `awssdk-sqs = { module = "software.amazon.awssdk:sqs", ... }`). compileOnly: the
  adopter's SDK version wins; only the `Message` model type and
  `MessageSystemAttributeName` are touched. Tests: `testImplementation(libs.awssdk.sqs)` —
  `Message.builder()` is pure data, so no LocalStack/containers needed.

### 2. `SqsMessageWorkUnitAdapter`

```kotlin
public class SqsMessageWorkUnitAdapter(
    private val queueName: String,
) : WorkUnitAdapter<Message>
```

- `queueName` is a constructor parameter because `Message` does not carry its queue — the
  poller knows what it polled. One adapter instance per queue/worker; document that. Accept
  either a bare name or a queue URL and normalize to the last path segment (URL passthrough
  is the common bug — pin it in tests).
- `describe`: `WorkUnit(id = message.messageId(), kind = "sqs_message", startedAt = now)` —
  unlike Kafka there's no id ambiguity: SQS always assigns `messageId`, so no lambda knob.
- `enrich`:
  - `MESSAGING_SYSTEM` → `"aws_sqs"` (the OTel semconv value — verify the current string at
    implementation time), `MESSAGING_DESTINATION_NAME` → the normalized queue name,
    `MESSAGING_MESSAGE_ID` → `message.messageId()`.
  - `MESSAGING_SQS_RECEIVE_COUNT` (new core constant,
    `"messaging_sqs_receive_count"`, Long) from
    `message.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]` — **only
    present when the poll requested system attributes**; parse defensively
    (`toLongOrNull()`), omit when absent or unparseable. KDoc: to get it, the
    `ReceiveMessageRequest` must ask for `ApproximateReceiveCount` — worth having, since
    receive count is the redelivery/DLQ-pressure signal.
  - `MESSAGING_PROCESS_DURATION_MS` from the outcome, `work_unit_id`/`work_unit_kind`, and
    the standard outcome mapping copied from 026's adapter: `Threw` →
    `error=true`/`error_class`/check-before-default `error_reason="exception"`;
    `Cancelled` → `cancelled=true` + default `cancel_reason`. Same precedence rules
    (`WorkUnitAdapter.enrich` KDoc).
- No body/attribute sniffing beyond the above — message payloads are handler territory
  (PII stance: the library never captures bodies).

### 3. Constants (core)

Only `MESSAGING_SQS_RECEIVE_COUNT` is new; it joins 026's messaging group in
`CanonicalFields` with the usual `CanonicalFieldsTest` value pin. Everything else reuses
026's constants — if 026 hasn't landed yet, land it first rather than duplicating.

### 4. Recipe + docs

- `docs/recipes/message-consumers.md`: the generic-envelope adapter section gets a pointer:
  "for Kafka and SQS these adapters ship — see `KafkaRecordWorkUnitAdapter` /
  `SqsMessageWorkUnitAdapter`"; the poll-loop example itself stays generic.
- `docs/CLAUDE.md`: module-layout entry (note the queue-name-as-constructor-arg reasoning
  and the receive-count attribute prerequisite — both are the questions adopters will ask).

### 5. Tests (kotest)

- Built `Message` through `withCanonicalLogBlocking` + the adapter: success line carries
  system/destination/message-id/duration; queue URL input → name normalized; receive count
  present when the attribute is set, absent otherwise (negative), absent when unparseable;
  thrown handler → error mapping; handler-set `error_reason` survives enrich
  (check-before-default pin); negative: no `error` on success, no body-derived fields ever.

## Acceptance

- An SQS poll loop needs only `SqsMessageWorkUnitAdapter(queueName)` +
  `withCanonicalLogBlocking` per message to emit contract-complete lines.
- Receive-count semantics (present/absent/prerequisite) pinned; SDK is compileOnly; the one
  new constant pinned; recipe updated.
