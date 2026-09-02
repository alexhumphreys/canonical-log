# Strict mode / no-op contribution detection for tests

**Status:** todo · **Modules:** `canonical-log-core`, `canonical-log-test`, docs
**Depends on:** nothing.
**Source:** 2026-09-02 design-explainer write-up (`docs/design-explainer.md` §5) — gap review.

> **Explainer note:** `docs/design-explainer.md` §5 currently states that misuse fails
> silently with no strict mode. If this lands, update that section (and §3.2's honest-limit
> paragraph) to point at the new detection mechanism.

## Problem

Every contract violation an adopter can commit — `CanonicalLog.put` where no unit is bound
(wrong-thread executor wrapping, detached coroutine after emit, contribution from a sink) —
degrades to a *silent no-op* and a quietly thinner canonical line. That is the correct
production behavior ("telemetry must never fail the operation it observes") but it means
propagation mistakes are only discoverable by noticing a missing field on a dashboard,
possibly weeks later. There is no opt-in way to make them loud in tests/CI.

`canonical-log-test` has `testCanonicalLogContext()` / `withBoundCanonicalContext()` for
positive cases but nothing that detects the *absence* of a binding at contribution time.

## Design (decision to make at implementation time)

Two candidate shapes — pick one, or ship both if the core hook is trivial:

### Option A: core diagnostic hook (mechanism, not policy)

A process-wide, set-at-startup callback in core, default no-op:

```kotlin
public object CanonicalLog {
    /** Invoked when an ambient contribution finds no bound unit. Default: no-op.
     *  Set once at startup/test-setup; must not throw in production configs. */
    @Volatile
    public var onUnboundContribution: ((key: String) -> Unit)? = null
}
```

Each ambient entry point (`put`, `increment`, `markFailed`, `markDegraded`) calls it on the
null-context branch. Production default stays exactly as today (null check, no allocation,
no behavior change). The test kit then provides the policy:

```kotlin
// canonical-log-test
failOnUnboundContributions {           // installs a throwing/recording hook, try/finally restores
    // test body
}
```

Cost: one volatile read on the ambient no-op path (currently free). Measure; if it shows up,
gate behind a single `@Volatile var strict: Boolean` checked before the callback read.

### Option B: test-kit-only, no core change

`canonical-log-test` captures via the existing lifecycle and additionally asserts, e.g.
`captureCanonicalLine(strict = true) { }` runs the block with a sentinel installed some other
way. Without a core hook this can only detect unbound contributions on threads the kit
controls — weaker, but zero core surface. Probably insufficient for the executor-wrapping
mistakes that motivate this item; note why if chosen.

### Non-goals

- No production-mode throwing. The swallow-and-record principle is load-bearing
  (anti-goals + `increment` KDoc). This is a *test/CI* affordance.
- Not a field registry / schema validation — stays an explicit anti-goal.

## Tests (kotest)

- Hook fires for each ambient entry point when unbound; never fires when bound.
- Hook does not fire for contributions inside emit (the deliberately-unbound emit window,
  `LifecycleReentrancyTest` semantics) — decide and pin: probably it SHOULD fire there too,
  since contributing from a sink is documented-discouraged; if so, note that in the
  `EmitFn` KDoc.
- Test-kit helper: a body doing a wrong-thread hop without `propagatingCanonicalContext()`
  fails the test; the same body with the wrapper passes.
- Production default: `onUnboundContribution == null` and the no-op path allocates nothing
  (existing behavior pinned).

## Acceptance

- An adopter can make "a contribution silently no-op'd" a test failure with one line of
  test setup, including for plain-executor hop mistakes.
- Zero behavior/perf change for adopters who don't opt in.
- `docs/design-explainer.md` §5 and the `CanonicalLog` KDoc updated.
