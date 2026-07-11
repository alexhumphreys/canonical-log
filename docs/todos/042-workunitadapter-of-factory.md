# `WorkUnitAdapter.of` — lambda factory for one-off adapters

**Status:** todo · **Modules:** `canonical-log-core`, docs
**Depends on:** nothing (023's `seed` hook already landed; the factory must mirror its
default).

## Problem

Real dogfooding feedback (2026-07-11): a one-off `WorkUnitAdapter` for an internal-only
envelope type ends up as a ~45-line named `object` implementing the interface, when
semantically it's two small closures over a private DTO nobody outside the file cares
about. The interface stays right for shipped/reusable adapters, but for the
recipe-style hand-rolled case (the exact case 041 routes people to) the
`object : WorkUnitAdapter<T> { override fun ... }` ceremony is pure boilerplate —
repeated parameter types, an extra named declaration, an extra `CanonicalLogContext`
import.

Not a builder DSL (anti-goal): constructors/interfaces stay the canonical form; this is
one factory function so a throwaway shape doesn't need a named class.

## Design

### 1. Factory in core

`WorkUnitAdapter` currently has no companion object — add an empty `public companion
object {}` (binary-compatible addition) and hang the factory off it, in
`WorkUnitAdapter.kt`:

```kotlin
public fun <T> WorkUnitAdapter.Companion.of(
    describe: (T) -> WorkUnit,
    seed: (CanonicalLogContext, T) -> Unit = { _, _ -> },
    enrich: (CanonicalLogContext, T, Outcome) -> Unit,
): WorkUnitAdapter<T> = object : WorkUnitAdapter<T> {
    override fun describe(input: T): WorkUnit = describe(input)
    override fun seed(ctx: CanonicalLogContext, input: T) { seed(ctx, input) }
    override fun enrich(ctx: CanonicalLogContext, input: T, outcome: Outcome) =
        enrich(ctx, input, outcome)
}
```

Decide at implementation time whether a member `fun of(...)` on the companion beats the
extension (member is more discoverable in completion; extension keeps the interface file's
API surface minimal — either is fine, pick one and note it in docs/CLAUDE.md).

- `seed` defaults to no-op, mirroring the interface default.
- KDoc: point back to the interface's contracts (must-not-throw, seed/enrich precedence,
  write-through-`ctx`) — the factory changes construction, not semantics. Note that
  delegation composition (`by delegate` decorators like `OtelSeedingAdapter`) works on
  factory-built adapters exactly as on named classes.

### 2. Docs

- `docs/recipes/message-consumers.md`: show the generic hand-rolled adapter in `of` form
  (or add it as the compact alternative alongside the named-class form — implementer's
  call, but the recipe is the natural home since one-off internal envelopes are its
  audience).
- `docs/CLAUDE.md`: one line in the core module-layout entry. Keep shipped reference
  adapters as named classes — the interface remains the canonical form; `of` is for
  adopter one-offs.

### 3. Tests (kotest, core)

- `of`-built adapter through `withCanonicalLogBlocking` + `withCanonicalLog`: describe
  identity lands, enrich fields land, outcome passed through correctly.
- `seed` default is a no-op; a provided `seed` lambda runs bound at open (reuse an
  existing seed-test shape).
- A throwing lambda hits the same swallow-and-record guards as a throwing interface
  method (`canonical_log_seed_error*` / `canonical_log_enrich_error*`) — should be free,
  but pin it.
- Delegation composition over an `of`-built delegate works.

## Acceptance

- A one-off adapter is expressible as `WorkUnitAdapter.of(describe = ..., enrich = ...)`
  with `seed` optional; no named class, no `CanonicalLogContext` import at the call site
  when types infer.
- Existing adapters compile untouched (companion addition is source- and
  binary-compatible).
- Recipe shows the compact form; reference adapters stay named classes.
