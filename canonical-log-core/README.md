# canonical-log-core

The kernel: the per-work-unit field accumulator (`CanonicalLogContext`), the work-unit
lifecycle (`WorkUnit`, `WorkUnitAdapter`, `Outcome`), the coroutine/thread binding bridge,
and the sink seams. Dependencies: `slf4j-api` + kotlinx-coroutines only — no framework, no
encoder, no OTel.

Most adopters don't depend on core directly — a starter or bundle pulls it in. Depend on it
directly when writing a custom entry point (see
[docs/recipes/message-consumers.md](../docs/recipes/message-consumers.md)) or a custom
adapter/sink.

## The API handlers see

- `CanonicalLog.put(key, value)` / `increment(key)` — contribute fields to the active unit.
- `CanonicalLog.markFailed(reason, ...fields)` / `markDegraded(reason, ...fields)` —
  semantic outcome, orthogonal to the lifecycle `Outcome`. No-ops when no unit is bound.
- `CanonicalFields` — `const` names for every field the library writes; reference them
  instead of string literals. Reference: [docs/fields.md](../docs/fields.md).

## Entry points (for integration authors)

- `withCanonicalLog(adapter, input, emit) { }` — suspend; binds via a
  `ThreadContextElement`, surviving dispatcher switches, `async` fan-out, nesting.
- `withCanonicalLogBlocking(...)` — blocking closure form. A suspend body that switches
  dispatchers under a blocking entry needs `withCanonicalCoroutineContext { }`.
- `openCanonicalWorkUnit` / `CanonicalWorkUnitScope` — the open/close pair for lifecycles
  that arrive as separate callbacks (receive on one thread, ack on another). The KDoc
  carries the invariants; the recipe above is the worked example.
- `ContextPropagation`: `Runnable`/`Callable`/`Executor.propagatingCanonicalContext()` for
  plain thread hops (`ExecutorService`, `@Async`) the coroutine bridge doesn't cover.

## Also in core

- `MdcCanonicalLineWriter` and `JsonCanonicalLineWriter` sinks ([chooser](../docs/sinks.md)).
- `MdcSeedingAdapter` — zero-dependency `trace_id`/`span_id` seeding from MDC
  (OTel-API flavour: [`canonical-log-tracing-otel`](../canonical-log-tracing-otel/README.md)).
- `CanonicalLogMdc` — mirrors `work_unit_id` into MDC for debug-log correlation
  (process-wide opt-out: `CanonicalLogMdc.enabled = false`).
- `canonicalLineMessage(fields)` — the shared human-summary formatter.

Semantics (nesting, cancellation, precedence, telemetry-never-fails) are specified in
[docs/CLAUDE.md](../docs/CLAUDE.md)'s decisions and gotchas sections and pinned by the core
test suite.
