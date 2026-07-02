# Java ergonomics for the ambient API

**Status:** todo · **Modules:** `canonical-log-core`

## Problem

For a "JVM" library the API is Kotlin-only in practice. From Java:
`CanonicalLog.INSTANCE.put(...)` (object without `@JvmStatic`); `increment(key)`'s default
`by = 1L` doesn't exist (no `@JvmOverloads`); `markFailed("x", new Pair<>("k", v))` forces
Java callers to construct `kotlin.Pair`s for the vararg. Cheap to fix now, breaking to fix
after adoption.

## Design (agreed direction)

- `@JvmStatic` on all four `CanonicalLog` functions; `@JvmOverloads` on `increment`.
- Java-friendly extras overloads: `markFailed(reason: String, extras: Map<String, Any?>)`
  (and same for `markDegraded`), on both `CanonicalLog` and `CanonicalLogContext`. Keep the
  vararg-Pair versions as the Kotlin-idiomatic primary.
- Check the rest of the public surface for Java friction: `withCanonicalLogBlocking` takes
  Kotlin function types (usable from Java as lambdas — fine); `CanonicalLogContext.increment`
  default arg also wants `@JvmOverloads`.
- Add a small Java test source set (`src/test/java`) with a compile-and-run smoke test
  calling `CanonicalLog.put/increment/markFailed` and `withCanonicalLogBlocking` from Java —
  this pins the ergonomics so a refactor can't silently regress them.

## Acceptance

Java smoke test compiles without referencing `INSTANCE`, `Pair`, or explicit default args;
existing Kotlin tests unchanged.
