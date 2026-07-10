# Lincheck linearizability checking for `CanonicalLogContext`

**Status:** todo · **Modules:** `canonical-log-core` (test scope only)
**Depends on:** nothing.
**Recommended model:** Opus 4.8 / Fable — designing a correct linearizability spec (especially
deciding what the conflict-path contract *is*) is the hard part; the code volume is small.

## Problem

The accumulator (`CanonicalLogContext`) is the concurrency-critical heart of the library, and
its correctness is currently argued by construction (`ConcurrentHashMap` + `merge`) and tested
probabilistically (`AccumulatorPropertyTest`, load tests). Probabilistic tests catch gross
races but not rare interleavings. One suspect path in particular is unexamined:

`increment()` (`CanonicalLogContext.kt:29-44`) detects a type conflict *inside* the `merge`
lambda, but records it via three separate `put`s *after* `merge` returns. Racing
`increment("x")` against `put("x", "str")` and `put("x", 5L)` has real windows: a conflict can
be detected against a value that has since been replaced by a Long (stale
`canonical_log_type_conflict=true` on a line whose field is a valid Long), the three conflict
fields can interleave with another thread's conflict (mixed `_key`/`_type` from different
conflicts), and `merge`'s lambda may be retried/invoked speculatively. None of this is
specified or tested.

`docs/CLAUDE.md` notes jcstress is "overkill for the current design" — Lincheck is the
lighter-weight modern alternative: declare the operations, and its model-checking mode
explores bounded thread interleavings **exhaustively and deterministically**, replaying any
failure as a readable trace. This is the difference between "no test has failed yet" and
"no interleaving up to bound N violates the spec".

## Design

### 1. Dependency and framework exception

Add `testImplementation("org.jetbrains.lincheck:lincheck:<latest 3.x>")` to
`canonical-log-core` only. Lincheck tests run under plain JUnit — this is a second deliberate
exception to the "Kotest only" rule (alongside `JavaErgonomicsTest`); document it in
`docs/CLAUDE.md`'s Testing section, replacing/expanding the jcstress note ("Lincheck is the
tool for exhaustive schedule exploration; jcstress remains overkill").

### 2. The spec

A `CanonicalLogContextLincheckTest` with operations:

```kotlin
@Operation fun put(key: KeyEnum, value: ValueChoice)   // small key domain: "a","b"; values: Long, String
@Operation fun increment(key: KeyEnum, by: Long)        // by in 1..3
@Operation fun snapshot(): Map<String, Any>
@Operation fun markFailed(reason: ReasonEnum)
```

Small domains are essential — Lincheck's state space explodes otherwise. Run
`ModelCheckingOptions` (primary; exhaustive within bounds) plus a `StressOptions` smoke pass.

The interesting design work is the **sequential specification** for the conflict path.
Options, in order of preference:

1. **Specify the current contract precisely and check linearizability against it.** Likely
   outcome: the conflict-flag writes are NOT linearizable with respect to a naive sequential
   model (the flags trail the merge). If so:
2. **Weaken the spec deliberately**: check linearizability of the *field values* only
   (exclude `canonical_log_type_conflict*` keys from state equivalence) and assert the
   conflict flags as an eventual/at-least-once property separately. Record the decision in
   the KDoc of `increment` — "conflict markers are best-effort diagnostics, not linearizable
   state" — if that's the honest contract.
3. If Lincheck finds a genuinely wrong outcome (dropped increment, torn snapshot, stale
   conflict flag on a healthy field): fix it (e.g. move conflict recording inside a `compute`
   loop, or make the markers a dedicated `AtomicReference`), in a **separate commit** from
   the test, per the one-fix-per-commit convention.

### 3. Scope boundaries

Only `CanonicalLogContext` — not the threadlocal binding, not the coroutine bridge (Lincheck
can't model dispatchers; that's todo 037's territory). `snapshot()` inclusion is load-bearing:
it checks emit-time reads never observe torn state relative to the sequential spec.

Model-checking runs can be slow; keep iteration bounds modest in the default test task and
gate a deeper configuration behind a system property (`-Dlincheck.deep=true`) that a nightly
CI job (todo 040) can flip.

## Acceptance

- Lincheck model-checking passes for the value-linearizability spec, with the chosen
  conflict-marker contract written down (KDoc on `increment` + `docs/CLAUDE.md` gotchas entry
  updated if the contract was weakened or a bug was fixed).
- Any bug found lands as its own commit with a Lincheck-derived regression explanation.
- `docs/CLAUDE.md` Testing section documents the Lincheck exception to the Kotest rule and
  supersedes the jcstress note.
- Core's **main** dependencies unchanged (slf4j + coroutines only).
