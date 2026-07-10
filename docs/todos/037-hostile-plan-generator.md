# Hostile nodes for the coroutine-plan property generator

**Status:** todo · **Modules:** `canonical-log-core` (test scope: `AccumulatorPropertyTest`
or a sibling `HostilePlanPropertyTest`)
**Depends on:** nothing.
**Recommended model:** Opus 4.8 / Fable — the hard part is encoding the cutoff/shadowing
semantics into the expected-value function correctly; a wrong oracle produces a test that
pins the wrong contract or flakes.

## Problem

`AccumulatorPropertyTest`'s `Action` ADT generates arbitrary *happy-path* coroutine
structures (fan-out, dispatcher switches, bare async against the receiver scope). The
documented contracts under stress — snapshot cutoff for detached work, `Outcome.Threw` from
failing children, CE→Cancelled with in-flight contributions, inner-shadows-outer nesting —
are each pinned by *hand-written single cases* (`BridgeContractTest`, `WithCanonicalLogTest`,
`NestedWorkUnitTest`). No test explores those behaviours **composed** in random structures,
which is where interaction bugs live (e.g. a nested unit inside a failing fan-out branch
inside a blocking bridge).

## Design

### 1. New `Action` variants

Extend the ADT (in a new spec file so the existing green property stays untouched as a
baseline):

- `Throws(child)` — runs child, then throws a marker exception. In a fan-out branch this
  cancels siblings.
- `NestedUnit(child)` — opens an inner `withCanonicalLog` around child (null adapter, its own
  recording emit). Inner contributions must appear on the **inner** line only.
- `BlockingBridge(child)` — `withContext(Dispatchers.IO) { withCanonicalLogBlocking-style
  hop }`: call a blocking function that uses ambient `CanonicalLog` + a
  `withCanonicalCoroutineContext` re-entry, exercising the suspend→blocking→suspend seam.
- `ExecutorHop(child)` — submit child's increments to a shared executor via
  `propagatingCanonicalContext`, **joined** before returning (so contributions must land).
- `DetachedLaunch(child)` — launch on an external scope, *not* joined, gated on a latch the
  test releases only **after** emit: contributions must be absent from the snapshot (the
  documented cutoff), and nothing throws.

### 2. The oracle

Replace the single expected-map with a three-way classification computed by walking the plan:

- `mustContain: Map<String, Long>` — contributions on paths that complete before emit
  (including joined hops and nested-unit-external contributions);
- `mustNotContain: Set<String>` — keys written only by `DetachedLaunch` bodies (use
  disjoint key namespaces per variant so classification is unambiguous — e.g. detached
  writes only `det_*` keys) and keys written only inside `NestedUnit` (they belong to the
  inner line);
- `mayContain` — contributions racing with cancellation (a `Throws` sibling cancelling a
  branch mid-flight). Assert nothing about these except type sanity.

For plans containing `Throws` not swallowed by structure, additionally assert the work unit's
outcome is `Threw` and the exception reaches the caller **after** the line was emitted.
For `NestedUnit`, assert the inner line's `parent_work_unit_id`/`work_unit_depth` chain
reconstructs the generated nesting exactly.

The disjoint-namespace trick is the load-bearing design decision: it converts "did the right
value land" into set membership, which stays decidable even when cancellation makes exact
sums indeterminate.

### 3. Determinism budget

Keep `checkAll` at ~200 iterations, `maxDepth = 3`, and make the latch/barrier coordination
timeout-free where possible (release latches in `finally`). Real dispatchers, not
`runTest` — the properties are about real parallelism; that's the existing file's precedent.

## Acceptance

- New property spec with the five hostile variants composed freely with the existing ones,
  green at 200 iterations, runtime ≤ ~30s.
- Oracle asserts `mustContain` sums exactly, `mustNotContain` keys absent, nesting chain
  exact, outcome classification correct for throwing plans.
- Any semantics gap discovered (a composition the docs don't define) gets decided and
  recorded in `docs/CLAUDE.md`'s gotchas before the test pins it — don't silently pin
  accidental behaviour.
