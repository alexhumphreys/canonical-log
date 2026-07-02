# canonical-log-test: test-kit module for adopters

**Status:** todo · **Priority:** 3 (top three) · **Modules:** new `canonical-log-test`

## Problem

Adopters will want to assert "this code's canonical line contains X" without hand-rolling a
`WorkUnitAdapter` + recording emit in every test (as this repo's own tests do — see the
`nullAdapter` copies in `WithCanonicalLogTest`, `JdbcCanonicalAutoConfigurationTest`, etc.).
`docs/CLAUDE.md` already lists this as a v0.2 candidate; the 2026-07-02 design review promoted
it — libraries that are easy to test against get adopted.

## Design (agreed)

New Gradle module `canonical-log-test` (add to `settings.gradle.kts`; it gets explicit-API +
publishing automatically from the root build's `:canonical-log-` prefix convention).
`build.gradle.kts`: `api(project(":canonical-log-core"))`,
`implementation(libs.kotlinx.coroutines.core)`, test deps mirror core's.

Package `io.github.alexhumphreys.canonicallog.test`, framework-agnostic (no kotest/JUnit deps
in main). Public surface:

```kotlin
public class CapturedCanonicalLine<R> internal constructor(
    public val fields: Map<String, Any>,       // snapshot taken at emit
    public val workUnit: WorkUnit,
    public val outcome: Outcome,
    private val blockResult: Result<R>,
) {
    public val result: R get() = blockResult.getOrThrow()
    public val exception: Throwable? get() = blockResult.exceptionOrNull()
    public operator fun get(key: String): Any? = fields[key]
}

public fun <R> captureCanonicalLineBlocking(block: (CanonicalLogContext) -> R): CapturedCanonicalLine<R>
public suspend fun <R> captureCanonicalLine(block: suspend CoroutineScope.(CanonicalLogContext) -> R): CapturedCanonicalLine<R>

// For unit-testing contributors directly (e.g. call a listener, assert on the context):
public fun testCanonicalLogContext(id: String = "test-work-unit", kind: String = "test"): CanonicalLogContext
public fun <R> withBoundCanonicalContext(context: CanonicalLogContext, block: () -> R): R
```

Implementation notes:
- Capture functions wrap `withCanonicalLog{,Blocking}` with a private `RecordingAdapter`
  (describe → `WorkUnit(UUID, "test", now)`, enrich records the `Outcome` and contributes
  **nothing** — captured fields are purely what the code under test wrote).
- Block exceptions are captured into `blockResult`, NOT rethrown — asserting on failure lines
  is a primary use case (`captured.fields["error"]`, `captured.exception`). Catch `Exception`
  only; `Error`s propagate, matching core (in that case no line was emitted anyway).
- `testCanonicalLogContext`/`withBoundCanonicalContext` use `@DelicateCanonicalLogApi`
  internals with `@OptIn` inside the module so adopters don't need the opt-in themselves.
  `withBoundCanonicalContext` = bind, try/finally restore previous.

## Acceptance

- Self-tests in the module: blocking success (fields + result), blocking failure (fields,
  outcome is Threw, `exception` set, nothing rethrown), suspend variant with an `async` child
  contribution, ambient `CanonicalLog.put` inside `withBoundCanonicalContext` lands in the
  test context, no leaked threadlocal after each helper.
- `docs/CLAUDE.md`: add module to the module-layout list; update the v0.2-candidates entry
  (test module) to done/removed; the "Testing" section's intended shape should reference this.
- Consider migrating one existing repo test to the kit as a dogfood example (optional).
