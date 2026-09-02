# `CanonicalLogContext.get`/`contains` — stop snapshotting to read one key

**Status:** todo · **Modules:** `canonical-log-core` (+ mechanical adapter touch-ups), docs
**Depends on:** nothing.
**Source:** 2026-09-02 design-explainer write-up (`docs/design-explainer.md` §4.6) — gap review.

> **Explainer note:** if this lands, update the check-before-default snippets/prose in
> `docs/design-explainer.md` (§1 precedence, §4.6) and the `WorkUnitAdapter.enrich` KDoc
> example, which currently show `ctx.snapshot()[key] == null`.

## Problem

The documented check-before-default pattern for handler-owned fields is
`ctx.snapshot()[key] == null` — which copies the entire accumulator to read one key. Every
adapter `enrich` that defends `error_reason`/`cancel_reason` pays a full-map copy (twice,
for both fields), and custom adapters copy the pattern because it's the only supported read.
Harmless at request granularity, but needless, and it obscures intent.

## Design

Two small members on `CanonicalLogContext`:

```kotlin
/** The current value at [key], or null. Same per-field linearizability as [snapshot]. */
public fun get(key: String): Any? = fields[key]

/** True if [key] currently holds a value. */
public fun contains(key: String): Boolean = fields.containsKey(key)
```

- KDoc both against the Lincheck-pinned model: individual reads are linearizable; there is
  still no atomicity *across* keys (same weakenings as `snapshot`, link to it).
- Sweep shipped adapters (`HttpWorkUnitAdapter` and any other check-before-default site)
  to `ctx.get(key) == null` / `!ctx.contains(key)`.
- Update the check-before-default guidance in `WorkUnitAdapter.enrich` KDoc,
  `CanonicalFields` precedence KDoc, and `docs/CLAUDE.md`'s precedence gotcha entry.
- Consider whether the Lincheck spec should gain a `get` operation — it's the same read
  path as `snapshotValue` minus the copy, so a one-line `@Operation` addition keeps the
  model honest. Cheap; do it.

Non-goal: no mutable-map exposure, no `entries`/iteration API — `snapshot()` remains the
only whole-map read, preserving the "writers serialize snapshots, never the live map" rule.

## Tests

- Kotest: `get`/`contains` reflect puts/increments; absent key → null/false.
- Lincheck: `get` operation added to `CanonicalLogContextLincheckTest` (bounds unchanged).
- Adapter tests stay green after the sweep (behavior-identical change).

## Acceptance

- No shipped code path calls `snapshot()` except at emit time and in tests.
- Custom-adapter guidance shows the cheap form.
