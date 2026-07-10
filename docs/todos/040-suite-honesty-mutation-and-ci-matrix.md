# Suite honesty: mutation testing, CI parallelism matrix, coroutine leak probes

**Status:** todo · **Modules:** root build, `.github/workflows`, `canonical-log-core`
**Depends on:** most valuable after 033–039 land (it measures *their* strength too), but can
ship any time.
**Recommended model:** Sonnet 5 — build/CI plumbing plus triage; escalate individual
surviving-mutant fixes to a bigger model only if one demands a semantics call.

## Problem

The concurrency suite's *coverage* is asserted nowhere: a test suite can run thousands of
iterations and still not fail when the `finally` around `unbind()` is deleted. Three cheap
meta-techniques keep it honest:

1. **Mutation testing** — does deleting/inverting the load-bearing lines actually fail a
   test?
2. **Parallelism variation** — `-XX:ActiveProcessorCount=1` forces preemption-driven
   interleavings and exposes tests that only pass because contention never happens; high
   parallelism does the opposite.
3. **Coroutine leak probes** — a property run that leaks a coroutine (a detached child the
   design says is "cut off") currently passes silently; `DebugProbes` can assert none remain.

## Design

### 1. Pitest on `canonical-log-core`

Add the pitest Gradle plugin (with the Kotlin-friendly config: `pitest-kotlin` isn't required
but exclude generated/inline noise; target classes `io.github.alexhumphreys.canonicallog.*`
in core only — starters are wiring, poor mutation targets). Defaults + `STRONGER` mutator
group. Run it once, triage:

- Survivors in the lifecycle tail (`unbind`, guard `catch`es, `safeEmit`), the bridge
  (`updateThreadContext`/`restoreThreadContext`), and `increment`'s merge are **must-kill**:
  add the missing test per survivor (each is precisely a bulletproofing gap).
- Survivors in log-message strings, KDoc'd best-effort diagnostics: annotate/exclude with a
  comment, don't chase 100%.

Wire as a manual + nightly Gradle task (`./gradlew :canonical-log-core:pitest`), **not** in
the per-PR pipeline (runtime). Record the accepted-survivor policy in `docs/CLAUDE.md`.

### 2. CI matrix

Extend the existing workflow with a nightly (or weekly) job running the library test suites
under:

- `-XX:ActiveProcessorCount=1` and `=2` (JVM arg via Gradle `Test.jvmArgs`, plumbed like the
  existing `-Ptest.jdk=17` override — a `-Ptest.cpus=N` property);
- the deep Lincheck configuration from 033 (`-Dlincheck.deep=true`) if landed;
- 035's growth-bound soak if it was demoted from the default task.

Keep the per-PR pipeline unchanged — this is a background trawl, failures file issues rather
than block merges.

### 3. `DebugProbes` leak assertion

`testImplementation(kotlinx-coroutines-debug)` in core. In the property specs
(`AccumulatorPropertyTest`, 037's hostile spec): `DebugProbes.install()` in `beforeSpec`,
and after each property run assert `DebugProbes.dumpCoroutines()` contains no live coroutines
originating from the test (filter by name/context — the dispatchers' own workers are
expected). Caveat: DebugProbes instruments globally and can slow suites; if the overhead is
noticeable, scope it to a dedicated leak-check case per structure class instead of every
iteration. Detached-launch cases (037) must *complete* their latch-released work before the
assertion — the check is "no coroutine outlives the test", not "no coroutine outlives the
unit".

## Acceptance

- `:canonical-log-core:pitest` task exists; must-kill zones at 100% killed (or each survivor
  individually justified in a committed baseline/config comment); policy noted in
  `docs/CLAUDE.md`.
- Nightly CI job runs library suites at CPUs∈{1,2} (plus deep Lincheck/soak when available)
  and is green.
- Property specs assert zero leaked coroutines via DebugProbes without destabilizing the
  default test task's runtime.
