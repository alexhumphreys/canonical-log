# Virtual-thread torture + threadlocal leak soak

**Status:** todo · **Modules:** `canonical-log-core` (test scope)
**Depends on:** 034 lands first (reuses its token-isolation assertion shape).
**Recommended model:** Sonnet 5 — well-specified; the only judgment call is calibrating the
soak so it's meaningful but not flaky, and the design below pins that.

## Problem

Virtual threads are verified end-to-end only via the sample app (`spring.threads.virtual.
enabled=true` + `ab`) — out-of-process, HTTP-shaped, not in `./gradlew test`. Two risks are
specific to virtual threads and untested in-repo:

1. **Carrier-thread reuse bleed.** Millions of virtual threads multiplex onto few carriers.
   A binding leaked onto a *carrier* (rather than the virtual thread) would bleed across
   unrelated virtual threads — a failure mode platform-thread tests structurally cannot hit.
   `ThreadLocal` on a virtual thread is per-virtual-thread, so the design should be safe,
   but `propagatingCanonicalContext` + pinning interactions deserve a direct test.
2. **Retention leak.** Each work unit binds a context into a `ThreadLocal`. On churning
   virtual threads this should be unreachable after the thread dies; on long-lived pools a
   missed restore retains the whole accumulator (fields included — potentially large). No
   test watches memory.

This also produces the evidence the `ScopedValue` open question (docs/CLAUDE.md) needs: a
baseline in-repo virtual-thread suite to diff a JEP 446 prototype against.

## Design

Tests are JDK-21+-only at *runtime*: the library targets 17 bytecode, so the test must not
reference virtual-thread API from code compiled against 17 — tests compile with the 25
toolchain already, but guard execution with an assumption (`Runtime.version() >= 21`) so the
CI `test-jdk17` job (which re-runs library suites on a real 17 launcher) skips them cleanly.

### 1. Torture (`VirtualThreadTortureTest`)

`Executors.newVirtualThreadPerTaskExecutor()`, ~100k work units (tune to keep <60s), each a
tokened `withCanonicalLogBlocking` doing the 034 storm body (token put, token-named key,
increments), with a subset hopping through a shared *platform*-thread pool via
`propagatingCanonicalContext` and back. Assert the 034 isolation properties: one line per
unit, own token only, exact sums, exact line count.

Add a **pinning variant**: units that hold a `synchronized` block or native sleep while
contributing (pins the virtual thread to its carrier), interleaved with unpinned units —
this exercises carrier reuse under the nastiest scheduling.

### 2. Leak soak (same file or `ThreadLocalLeakTest`)

Two complementary checks, both deliberately coarse to avoid flakiness:

- **Reachability**: run one work unit whose context `put`s a value wrapped in a
  `WeakReference`-tracked object; after the unit emits and the virtual thread dies,
  `System.gc()` + await the reference queue → the accumulator must become unreachable.
  This is deterministic-ish and catches "someone added a static registry" regressions.
- **Growth bound**: record `usedHeap` after warmup + GC, run ~500k churned units, GC again,
  assert used heap grew by less than a generous bound (e.g. 50 MB). Not a precise leak
  detector — a tripwire. If it flakes in CI, keep the reachability check and demote this one
  to the nightly job (todo 040).

### 3. Carrier residue

After the torture run, saturate the carrier pool (`parallelism` × barrier probes on fresh
virtual threads) asserting `currentCanonicalContext() == null` and clean MDC — the
virtual-thread analogue of 034's pool residue probe. (Probes run *on* fresh virtual threads;
what this catches is state that leaked to the carrier via MDC's or the JDK's plumbing.)

## Acceptance

- Torture test: ≥100k virtual-thread work units, mixed pinned/unpinned, zero bleed by the
  034 assertions; skipped (not failed) on JDK <21.
- Weak-reference reachability check proves a finished unit's accumulator is collectable.
- Carrier residue probes clean after the run.
- `docs/CLAUDE.md`: virtual-thread verification entry updated to point at the in-repo suite
  (no longer only the sample-app `ab` run), and the `ScopedValue` open question gains a note
  that this suite is the comparison baseline.
