# `CanonicalWorkUnitScope` — internal guards against double emit/unbind

**Status:** todo · **Modules:** `canonical-log-core`, docs
**Depends on:** nothing (024's graduation of the open/close API already landed).
**Source:** 2026-09-02 design-explainer write-up (`docs/design-explainer.md` §3.4, §5 item 3) — gap review.

> **Explainer note:** `docs/design-explainer.md` §3.4 and §5 describe emit-exactly-once and
> unbind-exactly-once as purely caller-owned invariants on the open/close path. If this
> lands, update both to mention the internal defense-in-depth guards.

## Problem

`openCanonicalWorkUnit`/`CanonicalWorkUnitScope` is now public API explicitly aimed at
adopters wiring their own consumer/listener/job-runner integrations. Its KDoc puts
emit-exactly-once and unbind-exactly-once entirely on the caller, and the servlet module's
`CanonicalLogAsyncEmitListener` shows the required `AtomicBoolean.compareAndSet` pattern —
but every new integration author must re-implement that guard correctly, and the failure
mode of getting it wrong (double line → double-counted dashboards; double unbind →
clobbered enclosing binding) is silent and hard to attribute.

The primitive can carry cheap defense-in-depth without weakening the documented contract.

## Design

Add idempotence guards inside the scope:

- `emit(emit)`: an `AtomicBoolean.compareAndSet(false, true)`; a second call is a no-op
  plus one WARN to the `io.github.alexhumphreys.canonicallog` logger (work unit id, "emit
  called more than once — dropped"). Matches the swallow-and-warn posture of `safeEmit`.
- `unbind()`: same shape. A second `unbind` must NOT restore again (restoring a stale
  `previousContext` over whatever is now bound is exactly the corruption the guard exists
  to prevent) — WARN and return.
- Optionally: `enrich` after `emit` gets a WARN too (the contribution still lands on the
  live context but missed the line — that's the caller bug worth surfacing).

The KDoc invariants stay as written — the guards are a backstop, not a license, mirroring
the adapter-must-not-throw policy. Do NOT remove the CAS from
`CanonicalLogAsyncEmitListener`: its guard also gates listener-side work beyond emit.
Decide whether the closure entry points route through the guarded methods (they should —
they already call `scope.emit`/`scope.unbind`, so this is free) and confirm no perf-relevant
path notices two extra atomics per work unit (it won't; a work unit already allocates a
UUID and a CHM).

## Tests (kotest, core)

- Double `emit`: exactly one line delivered to the `EmitFn`; one WARN; no throw.
- Double `unbind`: threadlocal + MDC hold the correct (first-restore) state afterward; a
  unit opened next on that thread records no phantom nesting.
- Concurrent double-finalize hammer: two threads racing `emit` → exactly one line (extend
  or mirror the shape of `CanonicalLogAsyncEmitListenerTest`).
- Existing closure-path suites stay green (guards must be invisible on the happy path).

## Acceptance

- A misbehaving integration produces one line + WARNs instead of duplicate lines or a
  corrupted thread binding.
- No public API signature changes; KDoc updated to mention the backstop.
- `docs/design-explainer.md` §3.4/§5 updated.
