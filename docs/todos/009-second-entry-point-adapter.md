# Second entry-point integration (Kafka listener or @Scheduled)

**Status:** todo · **Modules:** new sample or `canonical-log-kafka*`; possibly starter AOP

## Problem

Everything shipped is HTTP. `WorkUnitAdapter` *should* generalize to Kafka consumers and
scheduled jobs, but abstractions designed against one consumer usually have bugs against the
second. This is also the most likely adopter question ("how do I do this for my consumer?").
`docs/CLAUDE.md` already lists a Kafka contributor as the top v0.2 candidate.

## Design (sketch)

Two deliberately different scopes — pick based on appetite:

1. **Cheap validation (do first):** a documented sample in `samples/` — a `@Scheduled` job or
   a Kafka `@KafkaListener` wrapped in `withCanonicalLogBlocking` with a hand-written
   `WorkUnitAdapter<...>` (job name / topic-partition-offset in describe+enrich). Proves the
   abstraction, produces copy-pasteable docs. Watch for friction: anything the HTTP filter
   gets for free that a job author has to hand-roll (emit wiring, exclude/sampling, MDC)
   should be recorded as follow-up TODOs.
2. **Full starter (later, demand-driven):** `canonical-log-kafka` contributor
   (consume counts/durations, DLQ-aware) per the v0.2 notes, and/or a `@CanonicalWorkUnit`
   AOP annotation in a starter for scheduled/async methods.

Nesting warning: a batch consumer looping messages inside one poll will immediately raise the
nested-work-unit question — see `010-nested-work-unit-semantics`; don't let this TODO
accidentally define those semantics ad hoc.

## Acceptance (for scope 1)

Sample compiles and runs in the demo app; produces one canonical line per job run / message
with `work_unit_kind` distinguishing it; README or docs section showing the adapter.
