# `canonical-log-grpc` — server entry point + client contributor

**Status:** todo (larger; demand-driven — build when a gRPC adopter shows up)
**Modules:** new `canonical-log-grpc`, `canonical-log-core` (constants)
**Depends on:** 020 (writer type). 024's graduated open/close primitive is the foundation.

## Problem

gRPC is the cleanest possible integration surface — `ServerInterceptor`/`ClientInterceptor`
are framework-agnostic, universally wired, and bracket exactly one RPC — and services that
speak gRPC have no canonical-log story at all today. One module covers both directions:
inbound RPC = work unit (entry point), outbound RPC = fields on whatever unit is active
(contributor, the OkHttp analogue).

The subtlety to design around (and the reason this file exists rather than "just write an
interceptor"): gRPC guarantees *serial* listener callbacks per call but **not same-thread**
— so a single open-time ThreadLocal bind is wrong. Binding must wrap each callback.

## Design

### 1. New module

- `settings.gradle.kts`: add `"canonical-log-grpc"`. Package
  `io.github.alexhumphreys.canonicallog.grpc`.
- `build.gradle.kts`: `api(project(":canonical-log-core"))`,
  `compileOnly(libs.grpc.api)` — catalog entries for `io.grpc:grpc-api` (adopter's version
  wins; the interceptor/`Status`/`MethodDescriptor` APIs are ancient and stable). Tests:
  `testImplementation` `grpc-api`, `grpc-inprocess` + `grpc-stub` + a protoc-less service
  built with `MethodDescriptor` + `ServerCalls` directly if wrangling codegen in tests is
  disproportionate — implementer's call; the in-process transport is the requirement, no
  network sockets in tests.

### 2. Field constants (core)

Per OTel RPC semconv, underscored; pin values in `CanonicalFieldsTest`:

- Server: `RPC_SYSTEM` (`"rpc_system"`, value `"grpc"`), `RPC_SERVICE`, `RPC_METHOD`,
  `RPC_GRPC_STATUS_CODE` (numeric code as Long), `RPC_REQUEST_DURATION_MS`.
- Client contributor: `RPC_CLIENT_REQUEST_COUNT`, `RPC_CLIENT_REQUEST_DURATION_MS_TOTAL`,
  `RPC_CLIENT_ERROR_COUNT`.

### 3. Server entry point: `CanonicalLogServerInterceptor`

```kotlin
public class CanonicalLogServerInterceptor @JvmOverloads constructor(
    private val writer: CanonicalLineWriter,
    private val adapter: WorkUnitAdapter<GrpcCallInfo> = GrpcServerWorkUnitAdapter(),
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
) : ServerInterceptor
```

- `GrpcCallInfo`: the adapter input — wraps `MethodDescriptor`, `Metadata` (headers), and a
  mutable slot for the terminal `Status` (set at close; the adapter reads it in `enrich`,
  mirroring how `HttpExchange` carries the response).
- Lifecycle (all via the graduated core primitives, no servlet analogies leaking in):
  - `interceptCall`: create the context via `openCanonicalWorkUnit(adapter, callInfo)` and
    **immediately `unbind()`** — open-time binding on the transport thread is useless and
    leak-prone. Keep the `CanonicalWorkUnitScope` in the wrapper objects instead.
  - Wrap the `ServerCall` (`SimpleForwardingServerCall`): override `close(status, trailers)`
    to record the status into `GrpcCallInfo`, then run enrich → sampler (fail-open, same
    policy/logging as the HTTP filter) → emit, guarded by an `AtomicBoolean` (close can
    race with `onCancel`).
  - Wrap the `ServerCall.Listener` (`SimpleForwardingServerCallListener`): **bracket every
    callback** (`onMessage`, `onHalfClose`, `onReady`, `onComplete`, `onCancel`) with
    `bindCurrentCanonicalContext`-style install/restore of this call's context plus
    `CanonicalLogMdc.install/restore` — this is the delicate-API pairing documented on
    `bindCurrentCanonicalContext`, and it's what makes `CanonicalLog.put` inside handler
    code and the JDBC/OkHttp contributors resolve correctly regardless of executor
    threading. `onCancel` additionally triggers the terminal path with the cancellation
    semantics below.
- Status mapping in `GrpcServerWorkUnitAdapter.enrich`: `rpc_service`/`rpc_method` split
  from `MethodDescriptor.fullMethodName`; `rpc_grpc_status_code` from the recorded status;
  `Status.CANCELLED` (or `onCancel` without close) → `cancelled=true` + default
  `cancel_reason="cancelled"`, **not** `error=true` — client disconnects don't pollute
  error rates, same stance as HTTP 499; any other non-OK → `error=true` with
  `error_reason` check-before-default (`status.code.name.lowercase()` as the default);
  exceptions surfaced through `close` with `Status.UNKNOWN` behave like the HTTP 500
  heuristic. Async handler work beyond the callbacks (app executors) uses the existing
  `ContextPropagation` helpers — document, don't reinvent.
- Deadline expiry arrives as `Status.DEADLINE_EXCEEDED` via close — map to
  `cancelled=true`/`cancel_reason="deadline_exceeded"` (the async-timeout analogue).

### 4. Client contributor: `CanonicalLogClientInterceptor`

The OkHttp capture-at-submit pattern, verbatim in spirit:

- `interceptCall` runs on the caller's thread → capture `currentCanonicalContext()` (public
  API) and `nanoTime` there; no active unit → pure pass-through.
- Wrap the `ClientCall.Listener.onClose(status, trailers)`: against the **captured**
  context, `increment(RPC_CLIENT_REQUEST_COUNT)`, add elapsed to
  `RPC_CLIENT_REQUEST_DURATION_MS_TOTAL`, non-OK → `increment(RPC_CLIENT_ERROR_COUNT)`.
  Increments only; post-emit completions silently miss the snapshot (the documented
  cutoff — block on the call/future before returning if the unit must account for it).

### 5. Tests (kotest, in-process transport)

- Server e2e: unary OK call → one line, `rpc_service`/`rpc_method`/`rpc_grpc_status_code=0`,
  duration; handler `CanonicalLog.put` inside the service method lands (proves callback
  bracketing); throwing handler → non-OK status, `error=true`; client-initiated cancel →
  `cancelled=true`, no `error` (negative); streaming call with messages handled on multiple
  callbacks → exactly one line, contributions from every callback present.
- Binding hygiene: after a call completes, the worker thread's threadlocal is empty (open
  a second call on the same in-process executor and assert no `parent_work_unit_id` —
  the phantom-nesting regression).
- Client: call made inside `captureCanonicalLineBlocking` → count/duration/error fields on
  that line; call with no active unit → no fields, no NPE (negative).
- Emit-exactly-once under close/onCancel races — if a deterministic interleaving isn't
  reachable through the in-process transport, drive the wrapped call/listener directly with
  a fake, property-style over callback orderings (the `CanonicalLogFilterAsyncPropertyTest`
  pattern).

### 6. Docs

`docs/CLAUDE.md` module-layout entry (spell out the per-callback binding decision and the
close/onCancel single-emit guard — that's the entry future maintainers need); README gets a
gRPC quickstart (server interceptor registration + client channel interceptor) once the
module exists.

## Acceptance

- One line per inbound RPC (unary and streaming) with the `rpc_*` contract fields, correct
  cancellation-vs-error split, handler and contributor fields landing regardless of
  executor threading; no binding leaks across calls.
- Outbound calls contribute count/duration/errors to any active unit with capture-at-submit
  semantics; zero-cost pass-through otherwise.
- grpc-api is compileOnly; constants pinned; all of the above pinned by in-process tests.
