# Recipe: canonical log lines for message consumers

Message consumers — queue poll loops, Kafka-style listener containers, job runners — are
the next entry-point surface after HTTP. For Kafka and SQS these adapters ship — see
`KafkaRecordWorkUnitAdapter` (`canonical-log-kafka`) / `SqsMessageWorkUnitAdapter`
(`canonical-log-sqs`), which concretize this recipe for those brokers. For any other broker,
this recipe is the honest way to instrument one *today*, using only the public core API.

Everything below is broker-agnostic. Substitute your client's message type for the generic
`Envelope`; the shape is what matters, not the transport.

> **The test is the spec.** This page and
> `canonical-log-test/src/test/kotlin/io/github/alexhumphreys/canonicallog/test/MessageConsumerRecipeTest.kt`
> lift the same adapter, closure loop, split-callback listener, and suspend bridge. When the
> two drift, the test wins — it's compiled and pinned; this prose is not.

The building blocks, all public, no `@OptIn` required:

- `withCanonicalLogBlocking(adapter, input, emit) { … }` — the closure form, one call per
  message. Reach for this first.
- `openCanonicalWorkUnit(adapter, input)` → `CanonicalWorkUnitScope` — the open/close pair,
  for listeners whose lifecycle boundaries arrive as *separate callbacks* (receive on one
  thread, ack/nack on another). Graduated out of `@DelicateCanonicalLogApi` in todo 024: an
  adopter wiring their own consumer framework **is** an entry-point author, which is exactly
  who this API is for. The *binding internals* it drives (the `CanonicalLogContext`
  constructor, `bindCurrentCanonicalContext`, `CanonicalLogMdc.install/restore`) stay delicate.
- `withCanonicalCoroutineContext { … }` — bridges a blocking-opened unit into a coroutine so a
  suspend handler that switches dispatchers still sees the binding.

---

## 1. The envelope and the adapter

A broker delivers *some* message type. Model the broker-agnostic essentials as an `Envelope`:

```kotlin
data class Envelope(
    val id: String,       // broker message id
    val source: String,   // topic / queue / subscription name
    val payload: String,
)
```

The `WorkUnitAdapter<Envelope>` captures what's mechanically uniform across *every* consume —
identity in `describe`, the messaging fields + duration in `enrich`:

```kotlin
class MessageWorkUnitAdapter : WorkUnitAdapter<Envelope> {

    override fun describe(input: Envelope): WorkUnit =
        WorkUnit(id = input.id, kind = "message", startedAt = Instant.now())

    override fun enrich(ctx: CanonicalLogContext, input: Envelope, outcome: Outcome) {
        // Identity, mirroring the HTTP / scheduled-job adapters.
        ctx.put("work_unit_id", ctx.workUnit.id)
        ctx.put("work_unit_kind", ctx.workUnit.kind)

        // OTel messaging semconv, dot -> underscore for Loki. STRING LITERALS on purpose:
        // CanonicalFields constants are for fields the *library itself* writes, and there is
        // no messaging contributor yet (see the field-constants gotcha in docs/CLAUDE.md).
        // When a real canonical-log-kafka / -sqs contributor ships, these graduate to constants.
        ctx.put("messaging_system", "generic")
        ctx.put("messaging_destination_name", input.source)
        ctx.put("messaging_message_id", input.id)
        ctx.put("messaging_process_duration_ms", outcome.durationMs)

        // A thrown handler is a lifecycle failure; a business "dead-lettered" outcome is set by
        // the handler via markFailed and must NOT be clobbered here (check-before-default, the
        // same policy HttpWorkUnitAdapter uses for error_reason).
        if (outcome is Outcome.Threw) {
            ctx.put("error", true)
            ctx.put("error_class", outcome.cause::class.qualifiedName ?: "unknown")
            if (ctx.snapshot()["error_reason"] == null) {
                ctx.put("error_reason", "exception")
            }
        }
    }
}
```

Per-message values (`order_id`, `retry_attempt`, `cache_hit`) are the handler's job via
`CanonicalLog.put` / `ctx.put`, not the adapter's — the adapter stays mechanically uniform.

---

## 2. Closure-shaped poll loop — one unit per message

The common case: a loop that polls a batch and processes each message. Open **one work unit
per message**, never one per batch. (The "one line per unit of work" invariant is load-bearing;
a batch is N units, not one — see the multi-line-emit anti-goal in `docs/CLAUDE.md`.)

```kotlin
val adapter = MessageWorkUnitAdapter()
val writer: CanonicalLineWriter = LogstashCanonicalLineWriter() // or your sink

while (running) {
    for (envelope in broker.poll()) {
        withCanonicalLogBlocking(adapter, envelope, writer::write) {
            handle(envelope)   // CanonicalLog.put / increment inside here all land
        }
        broker.ack(envelope)
    }
}
```

`withCanonicalLogBlocking` binds the context on this thread, runs the block, classifies the
outcome, enriches, unbinds in `finally`, and emits exactly one line — in the right order, on
the right thread. You don't manage any of it.

---

## 3. Failure semantics and the DLQ

Two distinct failure shapes, distinguishable at query time by whether `error_class` is present:

- **Handler throws** → `Outcome.Threw` automatically → the adapter writes `error=true`,
  `error_class=<fqcn>`, `error_reason=exception`. No handler cooperation needed.
- **Business-level parking / dead-lettering with no throw** → the handler decides the message
  is undeliverable, routes it to a DLQ, and *marks* the line without raising:

  ```kotlin
  withCanonicalLogBlocking(adapter, envelope, writer::write) {
      if (attempts(envelope) > maxAttempts) {
          broker.deadLetter(envelope)
          CanonicalLog.markFailed("dead_lettered", "delivery_attempt" to attempts(envelope))
          return@withCanonicalLogBlocking   // no exception thrown
      }
      handle(envelope)
  }
  ```

  This emits `error=true`, `error_reason=dead_lettered`, `delivery_attempt=N`, and **no**
  `error_class` — the marked-failure signature (a business failure, not an uncaught exception).
  Query `| error="true" and error_class=""` to isolate marked failures from thrown ones.

**Retries are separate units.** Each redelivery/retry attempt is its own work unit and its own
canonical line (carrying its own `delivery_attempt`). Don't try to make one line span attempts.

---

## 4. Split-callback listener — open on receive, emit on ack/nack

Many listener containers hand you the message on one callback (`onMessage`) and the
acknowledgement outcome on *later* callbacks (`onAck` / `onNack`), possibly on a different
thread. The closure form can't express that split; the open/close pair can. This is exactly
the servlet-filter pattern (`runCanonicalHttpRequest`), spelled out:

```kotlin
class CanonicalMessageListener(
    private val adapter: WorkUnitAdapter<Envelope> = MessageWorkUnitAdapter(),
    private val writer: CanonicalLineWriter = LogstashCanonicalLineWriter(),
) {
    private val scopes = ConcurrentHashMap<String, ScopeState>()

    private class ScopeState(val scope: CanonicalWorkUnitScope, val envelope: Envelope) {
        val finalized = AtomicBoolean(false)   // emit-exactly-once guard
    }

    // Invariant 1: unbind() exactly once, in finally, on the RECEIVING thread.
    fun onMessage(envelope: Envelope) {
        val scope = openCanonicalWorkUnit(adapter, envelope)
        scopes[envelope.id] = ScopeState(scope, envelope)
        try {
            handle(envelope)   // contributions here land in this unit
        } finally {
            scope.unbind()     // the next message opened on this thread must not nest under this one
        }
    }

    fun onAck(envelope: Envelope) = finalize(envelope, error = null)
    fun onNack(envelope: Envelope, cause: Throwable) = finalize(envelope, error = cause)

    // Invariant 2: enrich before emit, each at most once — may run on another thread.
    // Invariant 3: terminal callbacks can race/repeat, so guard with an AtomicBoolean.
    private fun finalize(envelope: Envelope, error: Throwable?) {
        val state = scopes.remove(envelope.id) ?: return
        if (!state.finalized.compareAndSet(false, true)) return
        val outcome = state.scope.outcomeFor(error)
        state.scope.enrich(adapter, state.envelope, outcome)
        state.scope.emit(writer::write)
    }
}
```

The four invariants (see the `CanonicalWorkUnitScope` KDoc):

1. **`unbind()` exactly once, in a `finally`, on the thread that called
   `openCanonicalWorkUnit`.** The binding is thread-local; unbinding elsewhere leaves the
   receiving thread pointing at a finished unit, and the next message opened there records
   phantom nesting.
2. **`enrich` before `emit`, each at most once** — both may run later and on the ack thread.
3. **Emit-exactly-once is your job** when ack/nack can race or repeat — the `AtomicBoolean`
   `compareAndSet` is the guard (`CanonicalLogAsyncEmitListener` in `canonical-log-servlet` is
   the internal worked example).
4. **If none of that applies, use `withCanonicalLogBlocking`** (section 2) — it sequences all
   of this for you.

---

## 5. Suspend handlers from a blocking loop

If `handle` is a `suspend fun`, two options:

**Simplest — a coroutine per message:**

```kotlin
for (envelope in broker.poll()) {
    runBlocking {
        withCanonicalLog(adapter, envelope, writer::write) { handle(envelope) }
    }
}
```

**Blocking open + bridge**, when you've already opened the unit with the blocking primitive
(e.g. the split-callback listener) but the handler is suspend and switches dispatchers. The
blocking open only sets the *threadlocal*; a `withContext(Dispatchers.IO)` lands on a thread
where it's empty. Wrap the suspend body in `withCanonicalCoroutineContext { … }` **before any
dispatcher switch** — it reads the active threadlocal and lifts it into the coroutine context:

```kotlin
val scope = openCanonicalWorkUnit(adapter, envelope)
try {
    runBlocking {
        withCanonicalCoroutineContext {
            withContext(Dispatchers.IO) {
                CanonicalLog.increment("io_step_count")   // still lands in the unit
                handleSuspending(envelope)
            }
        }
    }
} finally {
    scope.unbind()
}
val outcome = scope.outcomeFor(null)
scope.enrich(adapter, envelope, outcome)
scope.emit(writer::write)
```

(See the "blocking entry + suspend body needs `withCanonicalCoroutineContext`" gotcha in
`docs/CLAUDE.md`.)

---

## 6. Composing with your app's own MDC machinery

If your app already runs an in-house coroutine `ThreadContextElement` that snapshots/restores
the *whole* MDC map per dispatch, know how it interacts with canonical-log's binding:

- `CanonicalLogElement` (the bridge behind `withCanonicalLog`) saves/restores **only the
  `work_unit_id` MDC key** per dispatch, not the whole map. It's a narrow write.
- Coroutine context elements update **left-to-right on dispatch and restore in reverse**. So
  **put `CanonicalLogElement` to the right of (after) a whole-map MDC element** in the context
  so its `work_unit_id` write happens last and wins:

  ```kotlin
  withContext(myWholeMapMdcElement + CanonicalLogElement(ctx)) { … }
  ```

  Put it to the left and the whole-map element's restore would stomp `work_unit_id` back to
  whatever the map held at snapshot time.
- kotlinx's `MDCContext` captures the MDC map **at construction time**. So constructing it
  *inside* an already-bound unit carries `work_unit_id` for free — the mirror wrote it before
  the snapshot:

  ```kotlin
  withCanonicalLogBlocking(adapter, envelope, writer::write) {
      runBlocking {
          withContext(MDCContext()) { /* MDC here includes work_unit_id */ }
      }
  }
  ```

In the common case you have no in-house MDC element and none of this applies — the bridge
mirrors `work_unit_id` on its own (opt out process-wide with `CanonicalLogMdc.enabled = false`).
