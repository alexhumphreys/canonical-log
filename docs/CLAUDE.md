# canonlog

Stripe-style canonical log lines for JVM services. Pulling in `canonlog-spring-boot-starter` (or a per-library module) auto-attaches HTTP, DB, and other observability fields to a single log line emitted at the end of each unit of work.

The POC works end-to-end on a Spring Boot sample app. This document captures the architecture, the decisions behind it, and the work queue.

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │  WorkUnit (HTTP request, Kafka      │
                    │  consume, scheduled job, ...)       │
                    └──────────────┬──────────────────────┘
                                   │ start
                                   ▼
                    ┌─────────────────────────────────────┐
                    │  CanonicalLogContext                │
                    │  (ConcurrentHashMap accumulator)    │
                    └──────────────┬──────────────────────┘
                                   │ bound to thread/coroutine via
                                   │ ThreadContextElement bridge
                                   ▼
        ┌──────────────┬───────────┴──────────┬──────────────┐
        ▼              ▼                      ▼              ▼
  ┌──────────┐  ┌──────────┐          ┌──────────────┐  ┌─────────┐
  │ HTTP     │  │ JDBC     │   ...    │ Handler code │  │ Future  │
  │ adapter  │  │ adapter  │          │ (markFailed, │  │ contrib │
  │          │  │          │          │  custom flds)│  │ -utors  │
  └────┬─────┘  └────┬─────┘          └──────┬───────┘  └────┬────┘
       │             │                       │               │
       └─────────────┴───────────────────────┴───────────────┘
                                   │ contribute fields
                                   ▼
                    ┌─────────────────────────────────────┐
                    │  emit on WorkUnit completion        │
                    │  → flat JSON, snake_case, one line  │
                    │  → stdout / Loki / etc.             │
                    └─────────────────────────────────────┘
```

The accumulator lives for the lifetime of one work unit. Contributors (libraries that hook into native extension points) and the handler both write into it. Exactly one canonical log line is emitted at the end. That's the whole product.

## Module layout

- `canonlog-core` — accumulator, `WorkUnit`, `WorkUnitAdapter`, `Outcome`, `CanonicalLogContext`, the `ThreadContextElement` bridge. Framework-agnostic.
- `canonlog-okhttp` — OkHttp `Interceptor` that contributes `http_client_request_count`, `http_client_request_duration_ms_total`. Framework-agnostic.
- `canonlog-jdbc` — `datasource-proxy` `QueryExecutionListener` that contributes `db_query_count`, `db_query_duration_ms_total`. Framework-agnostic.
- `canonlog-okhttp-spring-boot-starter` — auto-config wiring the OkHttp contributor.
- `canonlog-jdbc-spring-boot-starter` — auto-config wiring the JDBC contributor (uses `@JvmStatic` companion-object `@Bean` for the BeanPostProcessor per Spring's recommendation).
- `canonlog-spring-boot-starter` — umbrella starter that pulls in the others plus the HTTP request adapter (servlet filter that creates the work unit, captures `http_request_method` / `http_route` / `http_response_status_code` / `http_request_duration_ms`, emits the line).
- Sample app — exercises everything.

The split between `canonlog-<lib>` and `canonlog-<lib>-spring-boot-starter` follows Java ecosystem convention: contributors are framework-agnostic, starters are the integration glue. This keeps the door open for Quarkus/Micronaut starters later without duplication.

## Decisions and rationale

These are the decisions that shaped the design. Don't undo them without a strong reason — most have non-obvious justifications.

**Concurrency model.** The accumulator uses `ConcurrentHashMap` with `merge` for atomic increments. Not a synchronized block, not a single-thread assumption. Parallel fan-out within a request (multiple OkHttp calls in flight, parallel DB queries) needs to contribute correctly. Defensive snapshot at emit time to avoid concurrent modification during JSON serialization.

**Coroutine and virtual thread support.** The accumulator is bound to the current execution context via a `ThreadContextElement` that bridges to a thread-local. This means it works for plain threads, virtual threads, and coroutines uniformly. The bridge is the only piece that needs to know about coroutine internals; everything else is thread-local-flavored.

**Open question: `ScopedValue` (JEP 446).** The current implementation uses `ThreadLocal` + `ThreadContextElement`. On JDK 25, `ScopedValue` is the modern alternative and is a better fit for virtual threads and structured concurrency. The accumulator object itself stays mutable (`ConcurrentHashMap`) regardless — this is purely about how the *binding* propagates. Coroutine interop remains awkward either way: `ScopedValue` doesn't integrate with `CoroutineContext` automatically, so the `ThreadContextElement` bridge stays. The choice is whether the JDK-side simplification (and better virtual-thread story) justifies maintaining two binding mechanisms in parallel. Worth prototyping before v0.2 against the suspend-function sample endpoint, which is the case where the difference would actually show up.

**Outcome model.** Two orthogonal concepts:
- **Lifecycle outcome** (`Outcome.Completed` / `Outcome.Threw(cause)`): the library reports facts about whether the unit ran to completion or threw.
- **Semantic outcome** (`CanonicalLog.markFailed(reason, ...)` / `markDegraded(reason, ...)`): the handler expresses intent.

This is a deliberate departure from the original `Outcome.Success` / `Outcome.Failure(Throwable)` design. The two-axis model handles all the cases — exceptions, `Either`, `Result`, KEEP-0441 rich errors, business-level failures with no exception (e.g. `404 post_not_found`) — uniformly. The HTTP adapter defers to handler-set `error_reason` if present and only fills it in itself for 5xx responses or thrown exceptions.

The presence of `error_reason` without `error_class` is the signal that this was a marked failure rather than an uncaught exception. Worth documenting that pattern for query authors.

**Field naming and types.** OpenTelemetry semantic conventions are the source of truth, with dot→underscore mapping for Loki compatibility (`http.request.method` → `http_request_method`). Loki's LogQL sanitizes/mangles dotted keys; underscores are a technical requirement on this stack, not a stylistic preference.

Suffix conventions:
- Durations: `long` milliseconds, `_ms` suffix (e.g. `http_request_duration_ms`)
- Counts: `long`, `_count` suffix (e.g. `db_query_count`)
- Sums of durations: `_duration_ms_total` suffix (e.g. `db_query_duration_ms_total`)

**Schema versioning.** Include `canonical_log_version=v1` in every line.

**Type rules.**
- Lists: avoid. Decompose to count fields (`tag_count=3`) or delimited strings.
- Nested objects: never.
- Null: omit from output rather than emit `field=null`.

**Library scope discipline (mechanism, not policy).** The library provides the plumbing. PII redaction, cardinality limits, label promotion, sampling, and which fields are "expensive" are all operator/Detekt concerns, not library concerns. Resist requests to move policy into core.

**Contributors vs handlers.** The dividing line is uniformity:
- Contributors capture what's mechanically uniform across all uses of a library (every JDBC query has a duration; every OkHttp call has a status). LaunchDarkly-style flag instrumentation also qualifies — mechanical, bounded, low-cardinality, universally relevant.
- Handlers capture what's per-operation (`post_id`, `cache_hit`, `comment_count`).

If you can't explain a proposed contributor in those terms, it probably belongs in handler code.

**Exception cost on JVM.** The cost is in stack trace construction at throw time, not access time. Kotlin's `Result` type still wraps `Throwable`, so it doesn't escape this cost. `Outcome.Threw(cause)` carries the throwable but doesn't construct anything extra. Sealed classes / Arrow `Either` are the performant alternatives for expected failures — handler code, not library code.

**Three DSLs to ship; four to skip.**
- Ship: configuration DSL, scoped contribution (`canonical("ns") { ... }`), work unit declaration.
- Skip: contributor-authoring DSL (write a class), assertion DSL (negative assertions are first-class but no DSL on top), field registry DSL (operator concern), builder DSL (constructors are fine).

**OTel positioning.** OpenTelemetry is a data model and transport, orthogonal to the canonical log pattern. Ship `canonlog-otel` as an optional sink module later. Do not make OTel a core dependency.

## Subtle gotchas

These have already bitten or nearly bitten:

- **`withCanonicalLog<T, R>`** needs split type parameters (input vs return). Earlier draft had `<T>` for both, which is wrong.
- **`CanonicalLogContext` constructor is currently public** to make adapter testing easy. Before going public, switch to a `forTesting` factory or `@VisibleForTesting` annotation to keep the door open for changes.
- **Field ordering is non-deterministic** because of `ConcurrentHashMap` iteration. If alphabetical or namespace-clustered ordering matters at emit, sort there. Not a v0.1 concern.
- **`error=true` absent means false** — document for query authors that they need `| error="true"` rather than `| error!="false"`.
- **Timestamps in the sample default to local time with offset** (e.g. `+02:00`). Configure Logstash encoder to UTC via `<timeZone>UTC</timeZone>` in `logback-spring.xml` for the sample.
- **Nested work units** are not currently supported in any well-defined way. Sketch the semantics before adding.
- **Multi-line emit for long jobs is rejected** by design. Long jobs should be decomposed into multiple work units, each with its own canonical line. Don't add a `flush()` to the accumulator.
- **`withCanonicalLog` block has a `CoroutineScope` receiver** — `suspend CoroutineScope.(CanonicalLogContext) -> R`, not `suspend (CanonicalLogContext) -> R`. The receiver is load-bearing: without it, bare `async { ... }` inside the block resolves against the *outer* scope (e.g. `runTest`'s `TestScope`, `runBlocking`'s scope, or whatever wraps the call) and the new coroutine inherits the outer context, which has no canonical element. Contributions from inside the async coroutine are silently lost. The fix is the receiver, not `CopyableThreadContextElement` — that was investigated and didn't address the actual cause. Found and fixed during bridge bulletproofing; pinned by `BridgeContractTest` cases 3 and 8.
- **Nested `withCanonicalLog` calls compose via `ThreadContextElement`'s default merge** — innermost-`Key` wins, the outer accumulator stops receiving contributions while the inner is open, and resumes once the inner exits. This is incidental behaviour, not a contract: nested work units are listed in this doc as deliberately undefined ("Sketch the semantics before adding"). Do not write tests that pin this; treat as undefined until the semantics are sketched.
- **Blocking entry + suspend body needs `withCanonicalCoroutineContext`** — the blocking variant only sets the threadlocal. A suspend body that switches dispatchers (e.g. `withContext(Dispatchers.IO)`) lands on a thread where the threadlocal is empty. Wrap the body in `withCanonicalCoroutineContext { ... }` (which reads the active threadlocal and installs a `CanonicalLogElement` in the coroutine context) before any dispatcher switch.
- **Async servlet dispatch breaks the naive filter pattern** — `chain.doFilter` returns *immediately* for async-dispatched handlers (suspend controllers, `Callable`/`DeferredResult`, SSE). A filter that emits in its `finally` block emits before the handler has run. The current `CanonicalLogFilter` checks `request.isAsyncStarted` and defers emit to an `AsyncListener` for async paths. Found during suspend-endpoint integration; pinned by `CanonicalLogFilterTest` and the `ab` load test.
- **Spring suspend handlers need `kotlin-reflect` and `kotlinx-coroutines-reactor` on the classpath** — without them, `KotlinDetector.isSuspendingFunction` returns false and Spring tries to invoke the suspend method via plain reflection, which NPEs on the missing `Continuation`. Document this in the umbrella starter's docs once we have any.
- **Cancellation semantics are undefined.** Servlet container request timeouts cancel the controller's coroutine mid-flight (e.g. inside `withContext(Dispatchers.IO)`). What the canonical line should report (`Outcome.Cancelled`? Threw with `CancellationException`? `error_reason="cancelled"`?), whether contributions in flight at cancellation time land or are silently dropped, and how the AsyncListener.onTimeout path interacts with it — all currently undefined. Sketch the semantics before adopters depend on the behaviour.
- **Virtual threads work end-to-end.** Verified: with `spring.threads.virtual.enabled=true` in `application.properties`, the entire test suite (67 tests) passes, the suspend endpoint runs entirely on virtual threads (`Thread.currentThread().isVirtual == true` on every request entry), and `ab -n 5000 -c 50 -k http://localhost:8080/suspend/posts/1` produces 5000 lines with zero field bleeding. The sample app currently has virtual threads enabled by default. The bridge, filter, accumulator, and contributors all behave identically on virtual and platform threads — the threadlocal abstraction abstracts over both uniformly.

## Testing

**Framework.** Kotest. Don't introduce JUnit-style tests for new code.

**What to test at which level.**

- *Per-contributor tests* live in the contributor module and verify the contributor produces the expected fields given a controlled invocation (e.g. running an OkHttp call through the interceptor in isolation, asserting `http_client_request_count=1` and a sensible duration). These should not require Spring or Boot.
- *Per-starter tests* live in the starter module and verify the auto-configuration wires correctly — the bean is registered, the contributor is active, fields appear on a real request. Spring Boot test slices are appropriate here.
- *End-to-end tests* live in the sample app and verify the full pipeline: a real HTTP request produces one canonical line with the expected fields from all contributors plus handler-supplied fields. The two committed example log lines (`docs/examples/`) are effectively the spec for these tests.

**Negative assertions are first-class.** When testing a contributor or handler, assert what's *not* in the output as well as what is. `hasNoField(name)` and `hasNoFieldMatching(regex)` are the helpers. Common cases:
- Asserting that a contributor doesn't leak request bodies, headers, or query params it wasn't asked to capture.
- Asserting that `error=true` is absent on success paths (rather than asserting `error=false`, which would be wrong — the convention is "absent means false").
- Asserting that `error_class` is absent when a handler called `markFailed` without throwing.

These keep contributors honest about scope and prevent accidental field creep, which is the failure mode that turns canonical logs into a cardinality nightmare.

**Concurrency validation.** Any change to the accumulator, the `ThreadContextElement` bridge, or the binding mechanism (including a `ScopedValue` migration) needs a load test against the sample app. The standard check:

```
ab -n 1000 -c 10 http://localhost:8080/posts/1
jq -r 'select(.logger_name=="canonical") | .post_id' canonical.log | sort | uniq -c
jq -r 'select(.logger_name=="canonical") | .db_query_count' canonical.log | sort | uniq -c
```

For a single-endpoint test, every line should have the same `post_id` and the same steady-state count values. Distribution = field bleeding between requests = bug. Line count should match the request count exactly; off-by-one means a lifecycle bug.

**Concurrency testing approach for unit tests.** The load test catches gross failures end-to-end; unit tests catch subtle failures deterministically. The conventions:

- **Kotest** is the test framework. `kotlinx-coroutines-test` for suspending code, `kotest-property` for invariants under random schedules.
- **`runTest { }`** for any test involving coroutines or dispatchers. Gives deterministic execution with a virtual scheduler — no flaky tests from scheduling variance.
- **Property-based tests** for the accumulator's concurrency invariants. Generate N concurrent contributions, run them via `coroutineScope { repeat(N) { launch { ... } } }`, assert that totals equal sums and no contributions are lost. The schedule varies between runs, which catches bugs that single-shot tests miss.
- **`continually { }`** for negative timing assertions ("no canonical line should appear during this window because the work unit hasn't finished").
- **jcstress** is available if something forces the question of exhaustive schedule exploration, but don't reach for it without evidence the standard tests miss something. Overkill for the current design.

**The suspend-function path is verified.** A suspend controller (`/suspend/posts/{id}`) is wired into the sample app and was exercised under `ab -n 1000 -c 10`. All 1000 canonical lines emit with the expected fields and zero field bleeding (every line has the same `post_id`, `db_query_count`, `http_client_request_count`, status code).

**Bridge contract.** What the `ThreadContextElement` bridge guarantees, derived from the contract tests in `BridgeContractTest` and the property tests in `AccumulatorPropertyTest`:

1. **Same-dispatcher contributions** — fields put inside the `withCanonicalLog` block, on the entering dispatcher, are in the snapshot.
2. **`withContext` switch** — fields put inside `withContext(otherDispatcher)` are in the snapshot, on whichever thread that dispatcher uses.
3. **`async { }.await()`** — fields put inside an `async` block are in the snapshot after `await()` returns. **Important precondition:** the `async` must resolve against the `withCanonicalLog` block's `CoroutineScope` receiver — which it does naturally, because the block signature is `suspend CoroutineScope.(CanonicalLogContext) -> R`. If you call `async` from a place where a different `CoroutineScope` is the receiver (e.g. a top-level extension function called from inside the block, or `runTest`'s `TestScope` shadowing), the new coroutine inherits the wrong context and contributions are lost. See "Subtle gotchas" below.
4. **Parallel `async` fan-out** — `coroutineScope { async; async; ... awaitAll() }` works; per-branch increments sum correctly. The accumulator's `ConcurrentHashMap.merge` makes this safe.
5. **Nested `withContext` switches A→B→A** — context survives the round trip; contributions in every layer land.
6. **Orphaned `launch` outliving the parent** — by design, contributions made *after* `withCanonicalLog` has emitted are NOT in the emitted snapshot (the snapshot is a defensive copy taken at emit time). The launched coroutine still executes; its puts are silently swallowed for the purposes of *that* canonical line. This is the intentional cutoff for `launchDetached`-style fire-and-forget. The accumulator object remains valid; nothing throws.
7. **Sharing within the work unit** — child coroutines (launches and asyncs) share the parent's accumulator; their contributions are visible to the parent before emit.
8. **Blocking-entry → suspend bridge** — when the work unit is opened by a blocking entry point (e.g. the servlet filter calling `withCanonicalLogBlocking`) and a suspend body needs to bridge it into a coroutine context, use `withCanonicalCoroutineContext { ... }`. This reads the active threadlocal and lifts it into the coroutine context for downstream `withContext` switches. No-op if no work unit is active.

**Pinned by tests:**
- `canonlog-core:BridgeContractTest` (10 cases) covers points 1–8.
- `canonlog-core:AccumulatorPropertyTest` covers points 4 and 7 with random concurrent contributions (sum invariant) and arbitrary nested coroutine structures (bridge resilience invariant) — 200 iterations per property.
- `canonlog-spring-boot-starter:CanonicalLogFilterTest` covers the filter's lifecycle for sync, async, and exception paths.
- `canonlog-spring-boot-starter:CanonicalLogFilterAsyncPropertyTest` pins the emit-exactly-once invariant under arbitrary `AsyncListener` callback orderings (200 iterations of random sequences from `{onComplete, onError, onTimeout}`).
- `samples/spring-demo:FullStackPropertyTest` boots a real Spring + Tomcat + filter + bridge stack and fires random `Action` plans (sequential, withContext, async/await, parallel fan-out, nested coroutineScope) at `POST /property/run` — runs the property against **both virtual-thread and platform-thread Tomcat configurations** (100 iterations each, 200 total). Asserts every increment in the plan lands in the canonical line for that request. This is the test that would have caught the original async-dispatch bug — and is the regression guard against future entry-point lifecycle changes on either thread mode.
- The sample app's `/suspend/posts/{id}` plus `ab -n 1000 -c 10` covers the integration path end-to-end.

Any change to `CanonicalLogElement`, `bindCurrentCanonicalContext`, the threadlocal, the `withCanonicalLog` signatures, or `CanonicalLogFilter`'s lifecycle is a change to this contract — re-run the contract suite, the property tests, and the load test before merging.

**Async servlet dispatch is load-bearing.** Suspend controllers in Spring MVC are dispatched asynchronously: `chain.doFilter` returns *before* the handler completes. The filter detects this via `request.isAsyncStarted` and registers an `AsyncListener` to defer emit until completion. The single-emit invariant is enforced by an `AtomicBoolean`. If you change the filter, preserve: (a) emit-exactly-once across `onComplete`/`onError`/`onTimeout`, (b) sync-handler emit-inline, and (c) thread-local cleanup before the filter returns regardless of sync/async path.

**`canonlog-test` module (deferred, but planned).** Eventually, contributor authors outside this repo will need shared test infrastructure. The module is intended to ship:

- **`ContributorContractTest`** — a reusable test harness that every contributor should pass. The shape: start a work unit, run the contributor's instrumented operation, assert the expected fields appear with the expected types and naming conventions, assert nothing leaks that wasn't asked for. Concretely, an OkHttp contributor's contract test would verify `http_client_request_count` is a `long`, `http_client_request_duration_ms_total` is a `long` with the right suffix, and that no request bodies, headers, or query parameters appear in the output.
- **Negative assertion helpers** (`hasNoField`, `hasNoFieldMatching`) as first-class API — currently they live in the canonlog repo's own tests, but they're the most useful when contributor authors outside the repo can use them too.
- **Test fixtures for the accumulator and bridge** — a way to spin up a `CanonicalLogContext` in a test without needing a real work unit lifecycle, and a way to assert on the emitted line without parsing log output.

The reason to defer is concrete, not vague: until there are at least three contributors (probably `okhttp`, `jdbc`, and Kafka), you don't actually know which patterns are common enough to extract. Two contributors is a coincidence; three is a pattern. Extracting too early locks in the wrong abstraction.

The trigger to un-defer is the third contributor needing the same testing shape. At that point, extract from the existing per-contributor tests rather than designing the API from scratch.

**Contract testing across services.** Pact is in the broader stack and is the right tool when canonical log fields become part of an inter-service contract (e.g. an audit pipeline that depends on specific field names). Out of scope for the library itself, but worth noting for downstream consumers.

## What's done

POC modules listed in the layout section above are all implemented and working. The sample app demonstrates:

1. **Success case** (`GET /posts/1`, 200): contributor model collapses HTTP, DB client, JDBC, and handler-supplied fields into one line.
2. **Marked-failure case** (`GET /posts/999`, 404): handler calls `markFailed("post_not_found")`; line emits `error=true error_reason=post_not_found` with no `error_class`.

Real example log lines are committed in `docs/examples/`.

Load test (`ab -n 1000 -c 10 http://localhost:8080/posts/1`) produced 1000 canonical lines with no field bleeding between concurrent requests on the blocking servlet path. The same load test against `/suspend/posts/1` (the suspend-controller endpoint) also produces 1000 lines with no field bleeding — the accumulator + bridge holds up under real concurrency for both paths. Higher-concurrency check: `ab -n 5000 -c 50 -k http://localhost:8080/suspend/posts/1` sustained ~3940 req/s on platform threads (~4090 req/s on virtual threads), all 5000 lines with identical `post_id`, `db_query_count`, `http_client_request_count`, status — zero field bleeding at 5x the original concurrency. Same load test against the sync endpoint `/posts/1` on virtual threads sustained ~7800 req/s, also zero field bleeding.

Bridge bulletproofing produced:
- `BridgeContractTest` — 10 propagation guarantees pinned (same-dispatcher, `withContext`, `async`/`await`, parallel fan-out, nested switches, orphan launches, sharing semantics, blocking-entry → suspend bridge).
- `AccumulatorPropertyTest` — 2 invariants × 200 iterations: sum-of-contributions and bridge-resilience under arbitrary nested coroutine structures.
- `CanonicalLogFilterTest` — sync, async, and exception lifecycles for the servlet filter (single-emit guard).
- `withCanonicalCoroutineContext` helper for blocking-entry → suspend bridges.
- Async-aware `CanonicalLogFilter` that defers emit until `AsyncListener.onComplete` for suspend / `Callable` / `DeferredResult` / SSE handlers.

Stack: JDK 25, Gradle 9.5, Spring Boot 4, Kotlin 2.2.20.

## What's next

### Open semantics questions (sketch before implementing)

- **Cancellation outcome.** Coroutine cancelled by request timeout / client disconnect: what's the canonical line's outcome shape, what happens to in-flight contributions, how does it interact with `AsyncListener.onTimeout`? See "Subtle gotchas".
- **Nested work units.** Inner `withCanonicalLog` started inside an outer one: which accumulator wins for the duration, how do post-inner contributions in the outer block route, what does emit ordering look like?

### Before sharing with others

- [ ] Add a third sample endpoint that throws an exception — gives the third outcome shape (success, marked failure, thrown failure) side by side in the README.
- [ ] Write README with the three example log lines and an annotation showing which fields come from which module (HTTP adapter / JDBC contributor / OkHttp contributor / handler).
- [x] Try a `suspend fun` controller in the sample to verify the `ThreadContextElement` path end-to-end, not just the blocking servlet path. *(Done — `/suspend/posts/{id}` exists and passes `ab -n 5000 -c 50 -k`. Bridge contract pinned by `BridgeContractTest` (11 cases) and `AccumulatorPropertyTest` (200 iterations × 2 properties).)*
- [x] Re-run the test suite + load test with `spring.threads.virtual.enabled=true` to validate the virtual-thread story end-to-end. *(Done — sample now ships with virtual threads enabled by default; full suite + 5000-request load test pass with zero field bleeding.)*
- [ ] Configure UTC timestamps in the sample's `logback-spring.xml` (`<timeZone>UTC</timeZone>` on the encoder).
- [ ] Add `service_name` and `environment` to the sample via Logstash `customFields` so the example lines look like something you'd actually deploy.
- [ ] Consider switching `CanonicalLogContext` constructor visibility from public to a `forTesting` factory before anyone depends on it.

### v0.2 candidates (pick based on demand)

Ordered roughly by likely usefulness:

- **Kafka contributor.** Consumer-side: contribute `kafka_consume_count`, `kafka_consume_duration_ms_total`, `kafka_topic`, partition, offset. Producer-side: counts and durations. DLQ-aware.
- **WebFlux / Reactor support.** Verify the bridge works with `Mono`/`Flux` context propagation. May need a Reactor-specific context-element shim.
- **Resilience4j contributor.** Retry counts, circuit breaker state transitions, bulkhead rejections — all mechanically uniform, bounded, useful.
- **Micrometer tracing contributor.** For trace correlation: contribute `trace_id` and `span_id` if a span is active.
- **`canonlog-test` module.** See the Testing section for the intended shape. Trigger to un-defer is a third contributor (likely Kafka) needing the same testing pattern.
- **LaunchDarkly contributor.** Flag evaluations during the work unit. Bounded cardinality if done right.
- **Spring Security auth context contributor.** `auth_subject`, `auth_method`. Watch cardinality carefully.

- **`CopyableThreadContextElement` migration.** Trigger: when nested-work-unit semantics get sketched and require explicit merge control. The current plain `ThreadContextElement` works for all non-nested cases (verified by contract + property tests + load test) and the default same-`Key`-overrides merge gives incidental nested behaviour. Switch to `CopyableThreadContextElement` only if the nested-work-unit design requires per-child copy-on-spawn semantics or non-default merge.

### Deferred indefinitely (no plans, but worth recording)

- Configuration DSL, sampling hooks, sensitive-value redaction hooks.
- Cross-service propagation (pluggable propagator interface, W3C `traceparent` default, custom `request_chain` field).
- Custom backpressure-aware appender (separate from the app's main appender, block-not-drop default, bounded-blocking with degraded local-file fallback).
- Startup heartbeat endpoint (synthetic self-request through full filter chain to validate wiring at boot).
- Maven Central publishing.
- Arrow `Either` / KEEP-0441 integration helpers as separate modules.
- `canonlog-otel` sink module.

## Anti-goals

These have been considered and rejected. Don't add them without a new and compelling argument:

- **Contributor-authoring DSL.** Writing a `WorkUnitAdapter` or contributor as a regular class is fine. A DSL would obscure the lifecycle.
- **Assertion DSL.** Negative assertions are first-class API but there's no need for a fluent `expect { ... }` layer.
- **Field registry DSL.** Operators decide what fields to allow at the Detekt or pipeline level, not the library.
- **Builder DSL** for `WorkUnit` etc. Constructors are clearer.
- **Multi-line emit for long-running jobs.** Decompose into multiple work units instead. The "one line per unit of work" invariant is load-bearing.
- **Hiding distributed complexity behind a DSL.** Starters wire resilience patterns (Resilience4j, DLQ, retries) without hiding service boundaries. No "call this remote service like it's local" abstractions.
- **Lists or nested objects in field values.** Decompose to count fields or delimited strings; objects never.
- **PII redaction in core.** Operator concern. The library should make it easy to plug in, not opinionated about what's sensitive.
- **Sampling in core.** Same reasoning. v0.2+ may add hooks; core stays simple.
- **OTel as a core dependency.** Optional sink module only.

## Working with this codebase

A few notes for an agent picking this up:

- The decisions section above is load-bearing. If you find yourself wanting to change `Outcome`, the field naming, the contributor/handler split, or the "mechanism not policy" rule, stop and ask first — the rationale is non-obvious and was hard-won.
- The accumulator's concurrency story is the riskiest piece. Any change there needs a load test (`ab -n 1000 -c 10` against the sample) to verify no field bleeding.
- The `ThreadContextElement` bridge has only been verified on the blocking servlet path. The suspend-function path is on the to-do list above; until that's done, treat coroutine support as theoretical.
- Negative assertions (`hasNoField`, `hasNoFieldMatching`) are first-class. When adding behavior, add tests that assert what's *not* in the output as well as what is.
- Resist scope creep into the anti-goals list. If a feature request seems compelling, write down why before adding it — the list above exists because every item on it seemed compelling at some point.
