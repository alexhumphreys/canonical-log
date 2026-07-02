# Emit failures should never replace the block's result

**Status:** todo · **Modules:** `canonical-log-core`, `canonical-log-spring-boot-starter`

## Problem

Core's `EmitFn` contract is "must not throw" — but if it does, the exception propagates out of
`withCanonicalLog{,Blocking}` and *replaces the block's result*, including replacing a real
business exception with a sink failure (documented on the `EmitFn` typealias in
`WithCanonicalLog.kt`). This contradicts the library's own principle, applied to `increment()`
type conflicts in commit `aa34166`: telemetry must never fail the operation it observes. The
same applies to the starter's `CanonicalLineWriter.write` and
`CanonicalLogSampler.shouldEmit` (landed with 002).

## Design (agreed direction)

- In `withCanonicalLogBlocking` / `withCanonicalLog`: wrap `emit(ctx)` in try/catch
  (`Exception`, not `Error`), log one WARN to a real slf4j logger (core already depends on
  slf4j-api) — e.g. logger `io.github.alexhumphreys.canonicallog`, message including the work
  unit id and the exception — then continue to return the block's result / rethrow the block's
  exception as normal.
- Decide interaction with the enrich-exception path in `runEnrich`: today, if the block
  succeeded but enrich threw, that exception propagates. Reconsider under the same principle —
  probably it should also be swallowed-and-warned, with `canonical_log_enrich_error` fields
  (already written) as the record. Sketch both and pick one consistent policy.
- Filter: sampler + writer calls get the same treatment (or inherit it if the filter routes
  through a shared helper).

## Breaking-change notes

- Update `EmitFn` KDoc (delete the "propagates and replaces the block's result" paragraph).
- Existing pins to flip: `WithCanonicalLogTest` "a throwing emit (blocking/suspend variant)
  propagates and the threadlocal is still restored" (×2) and "if adapter.enrich throws on
  success, that exception propagates" — rewrite to pin the new swallow-and-warn behaviour
  (assert threadlocal still restored, block result returned, WARN event emitted — use a
  logback ListAppender as in `CanonicalLogFilterTest`).
- `docs/CLAUDE.md` gotchas + the EmitFn discussion need matching edits.
