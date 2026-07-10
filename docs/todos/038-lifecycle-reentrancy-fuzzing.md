# Reentrancy fuzzing of seed / enrich / emit

**Status:** todo · **Modules:** `canonical-log-core` (test scope)
**Depends on:** nothing.
**Recommended model:** Opus 4.8 / Fable — most cases will surface *undefined* behaviour that
needs a semantics decision before it can be pinned; that judgment is the work.

## Problem

The lifecycle guards (`runSeed`/`runEnrich`/`safeEmit`) are tested for **throwing**
adapters/emits, but not for **reentrant** ones — callbacks that call back into the library.
These are realistic, not adversarial: an `emit` that writes through logback whose appender
is itself instrumented; an `enrich` that calls `CanonicalLog.put` (ambient) instead of
`ctx.put`; a seed that opens a nested work unit; a writer shared between HTTP and scheduling
whose `write` fires while another unit is bound on the same thread. Today the behaviour of
each is an accident of implementation order (e.g. `emit` runs after `unbind` on the blocking
path — so ambient puts inside emit are no-ops — but on the suspend path emit runs *inside*
`withContext` where the element is still installed: ambient puts inside emit may mutate the
map **while it's being serialized**).

## Design

### 1. Case matrix (`LifecycleReentrancyTest`, Kotest)

For each lifecycle hook × each entry point (blocking, suspend, open/close scope), pin what
happens when the hook:

1. calls `CanonicalLog.put`/`increment` (ambient) — lands, no-ops, or races with snapshot?
   Decide per hook: seed/enrich run bound (should land — but via `ctx`, document ambient as
   discouraged); emit's contract should be **"the line is finalized; ambient writes inside
   emit are not observed"** — which likely requires passing `snapshot()` semantics or
   documenting the suspend-path discrepancy away (see §2);
2. calls `ctx.put` directly (emit receives the live context — a writer that mutates it
   affects nothing? or affects a *later* second writer? pin);
3. opens a **nested work unit** and completes it (should work: it's just nesting; assert the
   inner line emits and the outer is unaffected — including from inside `emit`, where the
   threadlocal state differs per entry point);
4. logs through a logger whose appender **recursively invokes the same writer** (guard
   against infinite recursion: assert bounded — the natural guard is that the recursive call
   has no bound context so its writes no-op; verify rather than assume);
5. throws *after* doing any of the above partially (guards must still restore binding).

### 2. Expected semantics decisions

At minimum these need answers written into KDoc/`docs/CLAUDE.md` before pinning:

- **Ambient writes inside `emit`:** recommend defining emit as observing a finalized line —
  simplest honest contract is "undefined which writes are visible; don't contribute from a
  sink". If the suspend/blocking discrepancy (element still bound vs already unbound) is
  judged a bug, align them (unbind-before-emit on the suspend path) as a separate commit.
- **Concurrent mutation during serialization:** `snapshot()` is a defensive copy, but
  `LogstashCanonicalLineWriter`/`JsonCanonicalLineWriter` each call `snapshot()` themselves —
  verify every shipped writer serializes a snapshot, never the live map (a live-map iterator
  is weakly consistent, not torn, but field sets could differ between two writers).

### 3. Style

Plain Kotest cases, not property-based — the matrix is small and each cell is a distinct
documented contract. Use `canonical-log-test`'s `captureCanonicalLine` where it fits;
recording emit lambdas elsewhere.

## Acceptance

- Matrix covered for all three entry points × five reentrancy shapes; each cell's behaviour
  pinned by an assertion **and** stated in the relevant KDoc (`EmitFn`, `WorkUnitAdapter.seed`
  /`enrich`).
- The suspend-vs-blocking emit-binding discrepancy is either aligned (own commit) or
  explicitly documented as contract.
- Recursion case proves bounded termination.
- Any behaviour change ships separately from the tests that pin it.
