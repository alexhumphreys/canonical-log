# CanonicalLog.time { } section-timing helper

**Status:** todo · **Modules:** `canonical-log-core`

## Problem

After counters, the most common handler pattern is timing a section ("how long did we spend
calling Stripe in this request?"). Today that's manual `System.nanoTime()` bookkeeping +
`increment` pairs in every handler.

## Design (sketch)

```kotlin
public inline fun <R> CanonicalLog.time(key: String, block: () -> R): R
```

- Emits `${key}_duration_ms_total` (increment) and `${key}_count` (increment 1) — matching the
  established suffix conventions in docs/CLAUDE.md. Duration charged on failure too (matches
  the JDBC contributor's "time-spent, not success-time" stance); exceptions propagate.
- No-op-safe like the rest of the ambient API (still runs the block, skips the fields).
- Needs a suspend story: an `inline` fun with a non-suspend block can't wrap suspend calls.
  Either provide `suspend fun timeSuspend`/overload, or document that `inline` makes the
  non-suspend version usable in suspend contexts for non-suspending blocks only — decide at
  implementation, don't ship a footgun.
- Nesting the same key: totals just accumulate — that's the point; document.

## Acceptance

Tests: success and throwing blocks both charge duration + count; works with no active work
unit; suspend usage story pinned one way or the other.
