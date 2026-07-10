# Emit-exactly-once under truly concurrent finalizers

**Status:** todo · **Modules:** `canonical-log-servlet` (test), `canonical-log-core` (test),
`canonical-log-test` (recipe test)
**Depends on:** nothing (033's Lincheck dependency is optional here — see §3).
**Recommended model:** Sonnet 5 — mechanical hammer construction over already-pinned
lifecycles.

## Problem

Emit-exactly-once is enforced by an `AtomicBoolean` CAS in `CanonicalLogAsyncEmitListener`
and demanded of callers by `CanonicalWorkUnitScope`'s KDoc invariant #3. Existing coverage
(`CanonicalLogFilterAsyncPropertyTest`, `CanonicalLogAsyncEmitListenerTest`) drives random
callback *orderings* — but sequentially, on one thread. Nothing fires `onComplete` /
`onTimeout` / `onError` **simultaneously from different threads**, which is what a container
race or an ack-after-timeout actually looks like. The CAS makes the single-flag part safe by
construction, but exactly-once also depends on everything *around* the flag: enrich-once,
unbind-once-on-the-right-thread, and the snapshot the winning emit publishes.

## Design

### 1. Listener hammer (`canonical-log-servlet`, extend `CanonicalLogAsyncEmitListenerTest`)

Per iteration (~2k iterations): build the listener over a fresh scope with a counting,
thread-safe `EmitFn`; start 2–3 threads parked on a `CyclicBarrier`, each firing one terminal
callback (`onComplete`/`onError`/`onTimeout`, chosen per-iteration, duplicates allowed —
containers do double-fire); release the barrier; join. Assert:

- emit count == 1, enrich observed exactly once (recording adapter);
- the single line's outcome is *one of* the fired callbacks' outcomes (either winner is
  legal; torn state — e.g. `cancel_reason=async_timeout` together with `error_class` from the
  losing `onError` — is not, **unless** the enrich-before-emit ordering makes it legal;
  decide and pin);
- no exception escapes to the callback threads (container threads must never see telemetry
  throws).

### 2. Consumer-recipe hammer (`canonical-log-test`, extend `MessageConsumerRecipeTest`)

The recipe's ack/nack guard is adopter-facing — hammer the recipe's own worked example the
same way: ack and nack (and a redundant second ack) racing from a barrier, exactly one line,
outcome one of the two, no double-enrich. This keeps the recipe honest, since its test is
declared "the spec when prose drifts".

### 3. Optional Lincheck pass

If 033 lands first, add a tiny Lincheck spec over the finalize path (operations:
`complete()`, `timeout()`, `error()`; state: emit count + published outcome) — model checking
turns "2k barrier iterations passed" into "no interleaving up to bound violates it". Skip if
033 hasn't landed; the barrier hammer stands alone.

### 4. What this is not

No change to production code is expected. If the hammer finds a real double-emit or torn
line, the fix is its own commit, and the failing seed/interleaving goes in the commit
message.

## Acceptance

- Barrier-based concurrent hammer in the servlet listener test: ~2k iterations, emit==1,
  enrich==1, sane single outcome, silent callback threads.
- Same shape over the message-consumer recipe's ack/nack guard.
- The "which outcome may win / what field combinations are legal" decision is written into
  the listener's KDoc (it's currently unstated).
- Runtime addition to the default test task ≤ ~20s.
