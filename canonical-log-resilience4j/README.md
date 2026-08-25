# canonical-log-resilience4j

Retries and rejections from [Resilience4j](https://resilience4j.readme.io/) on the canonical
line, with no change to any decorated call — register once against your registries:

```kotlin
CanonicalResilience4j.register(retryRegistry)
CanonicalResilience4j.register(circuitBreakerRegistry)
CanonicalResilience4j.register(bulkheadRegistry)
CanonicalResilience4j.register(rateLimiterRegistry)
CanonicalResilience4j.register(timeLimiterRegistry)
```

Spring Boot adopters don't do even that: add
[`canonical-log-resilience4j-spring-boot-starter`](../canonical-log-resilience4j-spring-boot-starter/README.md)
and every registry bean in the context is attached at startup.

## What it answers

A unit that took 3.2s and succeeded looks identical whether it was slow once or fast three
times behind a `Retry`; a unit an open breaker refused looks like any other `error=true`.
Both become visible on the line that already describes the request:

- `retry_attempt_count`, `retry_exhausted_count`, `retry_wait_duration_ms_total` — did this
  unit retry, how often, and how much of its wall-clock was backoff.
- `circuit_breaker_rejected_count` + `circuit_breaker_open_name`,
  `circuit_breaker_failure_count`, `bulkhead_rejected_count`,
  `rate_limiter_rejected_count`, `time_limiter_timeout_count` — was the call shed, and by which
  layer.
- `resilience_rejected` — the one boolean separating "we refused to call" from "the call
  failed". A shed unit also carries **no** `http_client_*` fields: the call never left the
  process.

Full types and semantics: [docs/fields.md](../docs/fields.md).

**Not a metrics replacement.** Resilience4j's Micrometer binder answers the global question
("how often is this breaker open?"). This answers the per-request one. Run both.

## How it attaches

Event publishers, not wrapping: registration hooks each instance's `EventPublisher`, and the
registry's `onEntryAdded` so instances created *later* (Spring's `@Retry("name")` creates them
lazily on first use) are covered too. Nothing about your decorations, annotations, or call
sites changes.

Resilience4j is `compileOnly` — only the registries' event surface and event types are
touched, so your own Resilience4j wins at runtime. Built against 2.3.x.

Notes:

- Registration is idempotent per instance (weak identity set), so a shared registry, a second
  `register`, or a context restart can't double-count.
- Events publish synchronously on the thread that made the decorated call — the thread the
  work unit is bound to — so nothing needs propagating. Two caveats: `ThreadPoolBulkhead` and
  `TimeLimiter` run the wrapped body on another thread (their *rejection*/timeout events still
  fire on the caller; the body itself needs `propagatingCanonicalContext()` like any hand-off),
  and bare Reactor/RxJava operator variants publish off-thread and silently no-op — the same
  gap as WebFlux generally. Under `withCanonicalCoroutineContext` the coroutine bridge holds.
- Telemetry never breaks the call it observes: with no work unit bound the contributions are
  silent no-ops, and an unexpected throw is recorded as `canonical_log_contributor_error`
  rather than propagating into your live call.
- Counters are instance-name-free on purpose — a name belongs in a field value
  (`circuit_breaker_open_name`), not a field name. Per-name breakdown is what Micrometer tags
  are for.
