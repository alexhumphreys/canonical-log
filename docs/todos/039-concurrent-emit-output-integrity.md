# Concurrent-emit output integrity (sink-level bleed)

**Status:** todo · **Modules:** `canonical-log-core` (test), `canonical-log-logstash` (test)
**Depends on:** benefits from 028 (`JsonCanonicalLineWriter`) being landed — it has; extends
its round-trip property to the concurrent case.
**Depends on:** nothing hard.
**Recommended model:** Sonnet 5 — well-bounded test work over existing serializers.

## Problem

Context-level bleed tests (034) can't see sink-level corruption: many units emitting
concurrently through one writer to one appender/stream can interleave *within a line* if any
layer buffers non-atomically — a torn line is data bleed at the output layer, and it's
exactly what a downstream parser chokes on at 3am. `JsonCanonicalLineWriterTest`'s round-trip
property is single-threaded; nothing tests concurrent `write` through a shared writer
instance, and nothing feeds the serializers adversarial values *while* concurrent.

## Design

### 1. Concurrent round-trip (`canonical-log-core`)

~200 threads/coroutines, each emitting ~50 lines through **one shared**
`JsonCanonicalLineWriter` whose `emitLine` appends to a synchronized collector (and a second
variant writing through a real logback logger + `ListAppender`, since logback's appender
synchronization is part of the deployed path). Field maps generated per-line with a unique
token plus adversarial values (newlines, quotes, backslashes, control chars, surrogates,
huge strings — reuse 028's generators). Then parse **every** collected line with the Jackson
test oracle and assert:

- every line parses (no torn/interleaved JSON);
- line count exact;
- each parsed line's token is internally consistent (every tokened field in a line carries
  the *same* token — a mixed-token line is sink-level bleed);
- round-trip value equality per line against what that unit emitted (keep a
  `ConcurrentHashMap<token, expectedMap>`).

Same shape for `MdcCanonicalLineWriter` (assert per-event MDC map integrity — MDC is
per-thread, so the risk is the flatten/restore window) and, in `canonical-log-logstash`,
for `LogstashCanonicalLineWriter` via marker inspection (`canonicalFields` from
`canonical-log-test` already flattens both shapes).

### 2. Snapshot-vs-late-increment race

While a unit is emitting (writer artificially slowed via a wrapping `emitLine` that parks
briefly), hammer `increment` on the same context from other threads. Assert the emitted line
is a **consistent prefix**: the counter value is between the pre-emit floor and the final
total, all self-diagnostic fields are internally coherent, and parsing never fails. This pins
"the snapshot cutoff is a clean cut, not a torn read" — complements 033's linearizability of
`snapshot()` with the real serializers in the loop.

### 3. Non-goals

No throughput/benchmark assertions (that's JMH territory, out of scope). No new production
code expected; if a writer is found serializing the live map instead of a snapshot, fixing
that is its own commit (see also 038 §2).

## Acceptance

- Concurrent round-trip green for all three shipped writers: every line parseable, exact
  count, single-token per line, per-line value equality — with adversarial value generators.
- Slow-emit late-increment race pins the consistent-prefix contract.
- Runtime addition ≤ ~20s in the default test task.
