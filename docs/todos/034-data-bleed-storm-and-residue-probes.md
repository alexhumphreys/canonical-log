# Data-bleed storm test + thread-pool residue probes

**Status:** todo · **Modules:** `canonical-log-core` (test scope), possibly a small helper in
`canonical-log-test` if it earns reuse
**Depends on:** nothing.
**Recommended model:** Sonnet 5 — the design below is fully specified; the work is careful
mechanical test construction.

## Problem

"No data bleed between concurrent work units" is the library's headline guarantee, but no
test asserts it as a **negative property**. The existing evidence is indirect: the property
tests assert *my contributions arrive* (bleed would only be caught if it corrupted a sum),
and the `ab` load tests assert uniform field values end-to-end but run out-of-process,
against one endpoint, and aren't part of `./gradlew test`. Two failure modes in particular
have no in-repo coverage:

1. **Cross-unit bleed**: unit A's contribution landing in unit B's line, via a shared
   executor, a missed restore in the bridge, or `propagatingCanonicalContext` capturing the
   wrong context.
2. **Pool residue**: a finished unit's context left bound to a pooled (or carrier) thread —
   the classic MDC-leak failure mode. It's invisible to per-unit assertions because the
   *next* unlucky task silently contributes to a dead accumulator; nothing currently probes
   thread state after the storm.

## Design

### 1. The storm (`DataBleedStormTest`, Kotest)

Run **N ≈ 500 concurrent work units** (mix of `withCanonicalLogBlocking` on a platform-thread
pool and suspend `withCanonicalLog` on `Dispatchers.IO`/`Default`), all contending on
**shared infrastructure**:

- one shared `ExecutorService` wrapped with `Executor.propagatingCanonicalContext()`,
- shared coroutine dispatchers,
- `Runnable`/`Callable.propagatingCanonicalContext()` hops (joined before the block returns —
  the documented contract).

Each unit gets a unique token `t`. Inside the unit: `put("token", t)`,
`put("field_$t", t)` (a token-named key — foreign keys are unmissable), and a known number of
`increment("hits")` calls, some executed via the shared executor hops. Collect emitted lines
with a thread-safe recording `EmitFn`.

Per-line assertions (the negative ones are the point):

- exactly **one** line per unit; total line count == N (no lost or duplicated emits);
- `token` equals the unit's own token; the only `field_*` key present is its own
  (`snap.keys.filter { it.startsWith("field_") } == listOf("field_$t")` — any foreign key is
  bleed, regardless of value);
- `hits` equals exactly that unit's contribution count (bleed-in would inflate it; bleed-out
  would deflate it).

Repeat the whole storm a handful of times inside the test to vary scheduling. Keep runtime
under ~30s so it stays in the default test task.

### 2. Residue probes (same spec or `ThreadResidueTest`)

After the storm completes and all units have emitted, probe **every thread** of each shared
pool: submit `poolSize` tasks that rendezvous on a `CyclicBarrier` (forcing each pool thread
to run exactly one probe), each asserting:

- `currentCanonicalContext() == null`;
- `MDC.get("work_unit_id") == null`.

Also probe after the **documented misuse modes** of `CanonicalWorkUnitScope`, as
behaviour-pinning tests (these pin what the KDoc invariants warn about, they don't bless it):

- `openCanonicalWorkUnit` with no `unbind` → the opening thread still holds the dead context;
  the *next* unit opened there records phantom `parent_work_unit_id`. Pin that this is what
  happens (it's the documented failure, and the test doubles as documentation).
- `unbind` called on a different thread → opening thread residue persists. Pin likewise.

### 3. Placement

Core test scope. If the token-storm harness comes out generic (it likely will — "run N
tokened units over this executor and assert isolation"), consider extracting a
`assertNoContextResidue(executor, poolSize)` helper into `canonical-log-test` in a follow-up,
not preemptively (extract-don't-design rule).

## Acceptance

- Storm test green in the default `test` task, asserting per-line token isolation, exact
  increment sums, and exact line count for ≥500 concurrent mixed blocking/suspend units over
  shared propagating executors.
- Barrier-based residue probe covers every thread of every shared pool used, asserting null
  context + clean MDC after the storm.
- Misuse-mode pins for skipped/wrong-thread `unbind` exist and reference the
  `CanonicalWorkUnitScope` KDoc invariants.
- `docs/CLAUDE.md` Testing section gains a line: bleed-freedom is now asserted in-process,
  not only by the `ab` load test.
