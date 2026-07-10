# `canonical-log-kafka` — consumer work-unit adapter + producer contributor

**Status:** todo · **Modules:** new `canonical-log-kafka`, `canonical-log-core` (constants),
docs (recipe update)
**Depends on:** 020 (writer type in core, for examples/tests), 024 (the recipe this
concretizes). Already a v0.2 candidate in docs/CLAUDE.md — this file is the agreed design.

## Problem

Kafka shows up on both sides of the contributor/entry-point split and today gets neither:

- **Consuming is an entry point** — one record handled = one work unit. Poll-loop adopters
  following the 024 recipe currently hand-roll the adapter and use string-literal field
  names (the recipe explicitly says constants arrive with this item).
- **Producing is a contributor** — a work unit (HTTP request, consumed message) that sends
  records should carry `kafka_produce_count` etc. the same way it carries `db_query_count`.
  The catch: producer acknowledgements arrive on producer IO threads with no ambient
  binding — the same shape as the OkHttp `enqueue()` problem, and solved the same way
  (capture-at-submit).

Scope discipline: this item is the **framework-agnostic module only**. A spring-kafka
starter (transparent `RecordInterceptor`-based work units) is a natural follow-up but is
deferred until someone asks — write a new todo then; don't build it speculatively here.

## Design

### 1. New module

- `settings.gradle.kts`: add `"canonical-log-kafka"`.
- `build.gradle.kts`: `api(project(":canonical-log-core"))`,
  `compileOnly(libs.kafka.clients)` — new catalog entries `kafka = "<latest 3.x>"` /
  `kafka-clients = { module = "org.apache.kafka:kafka-clients", ... }`. compileOnly so the
  adopter's client version wins; the APIs used (`ConsumerRecord`, `Producer`, `Callback`,
  `RecordMetadata`) are stable across 2.x/3.x. Tests: `testImplementation(libs.kafka.clients)`
  (for `MockProducer` and real record types).
- Package: `io.github.alexhumphreys.canonicallog.kafka`.

### 2. Field constants (core)

Add to `CanonicalFields`, grouped as "Messaging (consumer entry point)" and "Kafka producer
contributor". Names follow current OTel messaging semconv with the dot→underscore rule —
**check the semconv doc at implementation time and pin the exact strings**; expected set:

- Consumer-side (written by the adapter): `MESSAGING_SYSTEM` (`"messaging_system"`, value
  `"kafka"`), `MESSAGING_DESTINATION_NAME` (topic), `MESSAGING_KAFKA_PARTITION`,
  `MESSAGING_KAFKA_OFFSET`, `MESSAGING_MESSAGE_ID` (only when resolvable — see adapter),
  `MESSAGING_PROCESS_DURATION_MS`.
- Producer-side (written by the decorator): `KAFKA_PRODUCE_COUNT`,
  `KAFKA_PRODUCE_ERROR_COUNT`, `KAFKA_PRODUCE_DURATION_MS_TOTAL` — count/`_duration_ms_total`
  suffix conventions per docs/CLAUDE.md.

`CanonicalFieldsTest`: value pins + the no-alias check for each new constant.

### 3. Consumer side: `KafkaRecordWorkUnitAdapter`

```kotlin
public class KafkaRecordWorkUnitAdapter(
    private val messageId: (ConsumerRecord<*, *>) -> String? = { null },
) : WorkUnitAdapter<ConsumerRecord<*, *>>
```

- `describe`: `WorkUnit(id = messageId(record) ?: UUID.randomUUID().toString(),
  kind = "kafka_message", startedAt = now)`. No default header sniffing — which header (if
  any) carries a message id is adopter convention, so it's a lambda; document the common
  `{ it.headers().lastHeader("message-id")?.value?.decodeToString() }` shape in KDoc.
- `enrich`: the `MESSAGING_*` fields above from the record (topic/partition/offset are
  always available), duration from the outcome, `work_unit_id`/`work_unit_kind` (mirroring
  `HttpWorkUnitAdapter`), and the standard outcome mapping: `Outcome.Threw` →
  `error=true`/`error_class`/default `error_reason="exception"` with the check-before-default
  pattern for handler-owned `error_reason`; `Outcome.Cancelled` → `cancelled=true` +
  default `cancel_reason`. Reuse the exact precedence rules documented on
  `WorkUnitAdapter.enrich`.
- **No consumer wrapper/loop runner ships** — the loop is the adopter's (024 recipe). This
  module provides the adapter so the recipe's hand-rolled one becomes a one-liner.

### 4. Producer side: decorator, not interceptor

`ProducerInterceptor` was considered and rejected — record the reasoning in the module KDoc:
interceptors are instantiated reflectively from config (no way to resolve anything at
construction), and `onAcknowledgement` runs on producer IO threads where there is no
ambient binding and no way to recover which work unit sent the record. Instead, mirror
`DataSource.withCanonicalLogging()`:

```kotlin
public fun <K, V> Producer<K, V>.withCanonicalLogging(): Producer<K, V>
```

A delegating `Producer` (delegate everything; override `send`):

- On `send(record, callback?)`, capture `currentCanonicalContext()` on the **calling
  thread** (public API, no opt-in — same tag-first philosophy as OkHttp) and note
  `System.nanoTime()`.
- If a context was captured: increment `KAFKA_PRODUCE_COUNT` immediately (send attempted),
  and wrap the callback so that on completion it adds elapsed ms to
  `KAFKA_PRODUCE_DURATION_MS_TOTAL` and, on exception, increments
  `KAFKA_PRODUCE_ERROR_COUNT` — all against the *captured* context, not the callback
  thread's binding.
- No captured context → pure delegation, zero fields, zero overhead beyond a null check.
- Document the cutoff explicitly: acks landing after the work unit emitted are silently
  dropped from that line (snapshot-at-emit, same documented behaviour as OkHttp `enqueue`
  and orphaned launches). A work unit that must account for its sends should `flush()` or
  block on the returned future before returning.
- Contributions go through `increment` only (never `put`), per the type-conflict rules.

### 5. Recipe + docs updates

- `docs/recipes/message-consumers.md` (from 024): replace the hand-rolled example adapter's
  string literals with `CanonicalFields.MESSAGING_*` and mention
  `KafkaRecordWorkUnitAdapter` as the shipped Kafka case; update
  `MessageConsumerRecipeTest` to match (it is the recipe's spec).
- `docs/CLAUDE.md`: module-layout entry; move the Kafka bullet out of "v0.2 candidates";
  gotcha entry for the producer ack cutoff.

### 6. Tests (kotest, no broker — unit-level per the testing pyramid)

- Adapter: success/thrown/cancelled records against a hand-built `ConsumerRecord`; message
  id lambda used when non-null, UUID fallback otherwise; negative assertions (no `error` on
  success, no `messaging_message_id` when unresolvable).
- Decorator with `MockProducer`: send inside a `captureCanonicalLineBlocking` block →
  count/duration land; `errorNext(exception)` → error count; send with **no** active work
  unit → no NPE, no fields (negative); callback fires after emit → line unchanged, no
  throw; adopter's own callback still invoked in all cases.
- Concurrency sanity: parallel sends from `async` fan-out inside one suspend work unit sum
  correctly (accumulator merge already guarantees it; pin it anyway).

## Acceptance

- A poll-loop consumer needs only `KafkaRecordWorkUnitAdapter` + `withCanonicalLogBlocking`
  to get per-message lines with the `MESSAGING_*` contract fields.
- `producer.withCanonicalLogging()` makes any active work unit carry produce
  count/duration/errors with capture-at-send semantics; uninstrumented behaviour is
  byte-identical when no unit is active.
- New constants pinned; recipe + recipe test updated; kafka-clients is compileOnly.
