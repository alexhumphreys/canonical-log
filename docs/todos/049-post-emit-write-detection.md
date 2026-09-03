# Late writes through a captured context reference — give them a signal

**Status:** todo · **Modules:** `canonical-log-core`, docs
**Depends on:** nothing hard. Shares a diagnostic surface with [044](044-strict-mode-noop-contribution-detection.md)
(if 044 ships Option A's hook, this should reuse it rather than add a second one).
**Source:** 2026-09-03 design-explainer review — the one failure mode in §5 with no
detection story.

> **Explainer note:** `docs/design-explainer.md` §5 item 1 says detached-work
> contributions "may or may not land — snapshot cutoff, silently." If this lands, update
> that item to name the counter/WARN.

## Problem

§5 item 1 is the likeliest contract violation an adopter commits (an un-awaited `@Async`
or `GlobalScope.launch` in a service class is invisible at the call site) and the only one
the design leaves *silent*. Everywhere else a violation becomes a queryable signal: a type
conflict becomes `canonical_log_type_conflict`, a throwing adapter becomes
`canonical_log_enrich_error*`, a throwing emit leaves a WARN.

044 does not cover this case. 044 fires when an ambient contribution finds **no bound
unit** — which is the closure path, where `unbind` has already run by the time the
detached work resumes. It does not fire when the contributor holds a **captured context
reference** and writes into an already-emitted map:

- `OkHttpCanonicalInterceptor` resolving via the request tag (the `enqueue()` path — the
  tag deliberately survives the hop to OkHttp's dispatcher threads, including past the
  unit's end);
- any task submitted through `propagatingCanonicalContext()` that outlives the unit;
- a `CanonicalWorkUnitScope` reference held by an integration after its terminal callback.

In all three the `put`/`increment` succeeds against a live `ConcurrentHashMap`. The line
was serialized from a snapshot taken earlier, so the write is simply lost, with nothing
distinguishing it from a field the code never wrote.

## Design

Mark the context finalized at emit, and count writes that arrive after it.

- Add an internal `@Volatile var finalized: Boolean` (or an `AtomicBoolean`) to
  `CanonicalLogContext`, set by `safeEmit` **after** the snapshot is taken.
- Ambient and direct writes (`put`, `increment`, `markFailed`, `markDegraded`) check it on
  the write path. When set: perform the write as today (the map is still a valid object;
  refusing the write buys nothing and risks surprising a contributor mid-teardown),
  increment a `canonical_log_late_write` counter on the context, and WARN **once per
  context** to the `io.github.alexhumphreys.canonicallog` logger with the work-unit id and
  the offending key.
- The counter lands on the finalized map, i.e. *after* the line is gone — so it is not
  queryable on the line itself. That is the honest limit and should be stated: the
  operator-facing signal here is the WARN plus, if 044's hook exists, a test-time failure.
  Do **not** try to re-emit or amend the line; §4.4's argument against a background queue
  applies with more force to a second line for the same unit.
- Cost on the happy path: one volatile read per write. Measure against the hot-path
  allocation budgets from PR #27; if it registers, fold the check into the existing
  null-context branch rather than adding a second one.

## Tests (kotest, core)

- Write after emit: exactly one line, the late field absent from it,
  `canonical_log_late_write` present on the post-emit snapshot of the context, one WARN.
- Repeated late writes: counter increments, WARN fires once (not once per write).
- Concurrent late writes from several threads: counter total is exact (it is an
  `increment`, so this is the existing atomicity contract).
- Happy path unchanged: no `canonical_log_late_write` key on any existing suite's line —
  guards must be invisible when nobody misbehaves.
- OkHttp: an `enqueue()` call whose callback resolves the tag after the unit ended hits
  the late-write path rather than vanishing.

## Acceptance

- A detached contribution that misses the snapshot produces a WARN naming the unit and
  key, instead of nothing.
- No public API signature changes; no behavior change on the happy path.
- `docs/design-explainer.md` §5 item 1 updated to point at the signal.
