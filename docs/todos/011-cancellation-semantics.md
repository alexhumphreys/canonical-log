# Define cancellation semantics

**Status:** todo (design before code) · **Modules:** `canonical-log-core`, `canonical-log-spring-boot-starter`, docs

## Problem

`CancellationException` is currently caught as a plain `Exception` in `withCanonicalLog`, so a
client disconnect / request timeout produces `Outcome.Threw` → `error=true` on the line,
polluting error rates with non-failures. Also undefined: which in-flight contributions land,
and how this interacts with `CanonicalLogFilter`'s `AsyncListener.onTimeout` (which currently
synthesizes a `TimeoutException` and forces status 500). `docs/CLAUDE.md` flags all of this as
undefined.

## Design (recommended from the 2026-07-02 review)

- New outcome: `Outcome.Cancelled(durationMs, cause)` — produced when the block throws
  `CancellationException` (check via `is CancellationException`, and rethrow it after
  enrich/emit so structured concurrency is not broken; never swallow CE).
- Line shape: `cancelled=true`, no `error=true`. Adapters decide their own mapping
  (`HttpWorkUnitAdapter`: probably status 499-style semantics — decide; don't force 500).
- In-flight contributions: whatever is in the accumulator at emit time lands (same snapshot
  cutoff as everything else); document, don't engineer.
- Filter `onTimeout`: reconsider the synthesized `TimeoutException` → maybe
  `cancelled=true` + `cancel_reason="async_timeout"` instead of error; align whatever is
  chosen with the suspend path so the two entry points tell the same story.
- `Outcome` is a public sealed class — adding a subtype is source-breaking for exhaustive
  `when`s in adopter adapters. Fine pre-1.0; note in changelog.

## Acceptance

Tests: cancelled coroutine inside `withCanonicalLog` → one line, `cancelled=true`, no
`error=true`, CE rethrown, threadlocal clean; filter timeout path pinned;
`docs/CLAUDE.md` open-question + gotcha entries replaced with the defined contract.
