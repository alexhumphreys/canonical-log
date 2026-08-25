# canonical-log benchmarks

JMH harness for the canonical-log hot path. **Not published** — this module exists to produce
numbers, not artifacts.

```bash
./gradlew :benchmarks:jmh                                   # everything (~5 min)
./gradlew :benchmarks:jmh -Pjmh.args='Contribution'         # one class
./gradlew :benchmarks:jmh -Pjmh.args='Put -prof gc'         # with allocation profiling
./gradlew :benchmarks:jmh -Pjmh.args='-f 1 -wi 3 -i 3'      # quick, noisier
```

`-Pjmh.args` is passed straight through to JMH's CLI.

## Relationship to `AllocationBudgetTest`

The per-PR allocation gate is `canonical-log-core:AllocationBudgetTest`, not this module. It
measures the same operations with `getThreadAllocatedBytes` in about a second, and it *fails
the build* when the hot path starts allocating more than its budget.

This module covers what that test can't: **throughput**. It also serves as an independent
cross-check of the byte figures — `-prof gc` derives allocation from GC bookkeeping rather
than per-thread TLAB accounting, so agreement between the two is meaningful. They agree today.

Benchmarks are written in Java so JMH's annotation processor runs without pulling kapt into the
build; the side benefit is that they exercise the same Java-caller surface `JavaErgonomicsTest`
pins.

## What's measured

| Benchmark | Question it answers |
|---|---|
| `NoWorkUnitBenchmark` | What does a `CanonicalLog` call cost on a path with **no** work unit open — startup, library internals, adopter unit tests? |
| `ContributionBenchmark` | What does one field contribution to an open unit cost, ambient vs. direct, `put` vs. `increment`? |
| `WorkUnitLifecycleBenchmark` | What does one whole work unit cost — open, bind, MDC-mirror, contribute, enrich, unbind, emit? Parameterised at 0 and 10 fields to separate lifecycle from contribution. |
| `CanonicalLineJsonBenchmark` | What does the JSON sink cost at emit time, and do hostile field values (quotes, newlines → the per-character escape path) cost meaningfully more than clean ones? |

## Reference figures

Apple aarch64, JDK 25 (temurin), `-f 1 -wi 3 -i 3`. Treat these as *shape*, not absolutes —
they exist so a future run that looks structurally different is recognisable as such.

| Benchmark | ns/op | B/op |
|---|---|---|
| `put`, no work unit open | 0.77 | 0 |
| `increment`, no work unit open | 0.77 | 0 |
| `CanonicalLog.put` (ambient, unit open) | 5.0 | 0 |
| `ctx.put` (direct) | 4.7 | 0 |
| `CanonicalLog.increment` | 6.3 | 24 |
| `snapshot()` (2 fields) | 15.3 | 192 |
| `withCanonicalLogBlocking`, 0 fields | 42.8 | — |
| `withCanonicalLogBlocking`, 10 fields | 105.0 | ~586 |
| `canonicalLineJson`, 10 fields | 340 | — |
| `canonicalLineJson`, 40 fields | 2479 | — |

Two things worth knowing about these:

- **`put` allocates nothing at all.** Not "a little" — zero bytes, on the open-unit path and
  the no-unit path alike.
- **`increment` is down to the boxed `Long` and nothing else.** It was 80 B/op until this
  harness surfaced that its `merge` remapping lambda captured a mutable local, costing an extra
  lambda instance plus a `Ref.ObjectRef` on every call. 24 B/op is the floor for a
  `Map<String, Any>` accumulator. See the allocation-budget entry in `docs/CLAUDE.md`.
- **Escaping is not a cliff.** Field values full of quotes and newlines render at roughly the
  same cost as clean ones, so a canonical line carrying user-supplied strings doesn't pay a
  surprise penalty at emit time.
