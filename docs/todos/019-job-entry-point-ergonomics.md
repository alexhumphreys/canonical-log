# Non-HTTP entry-point ergonomics (shared injectable writer)

**Status:** todo · **Modules:** `canonical-log-spring-boot-starter`, `canonical-log-scheduling-spring-boot-starter` (+ maybe a shared writer module)

## Problem

Surfaced building the second entry point (todo 009). The emit sink (`CanonicalLineWriter` /
`LogstashCanonicalLineWriter`) lives in the HTTP umbrella starter, and the auto-config resolves
it via `ObjectProvider` but registers no **bean** — so nothing else can inject the same sink.

Now that the scheduling starter shipped (2026-07-04, transparent `@Scheduled` instrumentation),
this is concrete: `CanonicalSchedulingAutoConfiguration` **can't depend on the HTTP umbrella
starter** (that would invert the module split — starters are meant to be independent), so it
**re-hardcodes the logstash emit** (`logger.info("canonical", StructuredArguments.entries(...))`).
That's now a *second copy* of the 2-line sink, and:

- a scheduled job doesn't pick up a user's custom `CanonicalLineWriter` bean the way the HTTP
  filter does — the two sinks silently diverge;
- the logger name (`"canonical"`) is hard-coded in two places.

(009 originally hand-rolled the writer in the *sample* `ReportingJob`; the scheduling starter
removed that, but pushed the same hardcoding down into the starter itself — so the friction
moved rather than vanished.)

Non-issues (verified, don't "fix"): MDC correlation is free everywhere (`openCanonicalWorkUnit`
installs `work_unit_id`). describe/enrich is the intended model, not friction.

## Design (sketch — pick one, demand-driven)

1. **Move `CanonicalLineWriter` + `LogstashCanonicalLineWriter` to a shared low module** (e.g. a
   small `canonical-log-spring-boot-common`, or down into a writer-only module both starters
   depend on) and register it as a `@ConditionalOnMissingBean` bean there. Then both the HTTP
   filter and the scheduling handler inject the same sink, a user bean overrides both, and the
   logstash-emit logic exists once. This is the real fix for the two-copy problem.
2. **A `CanonicalWorkUnitRunner` helper bean** that wraps `withCanonicalLogBlocking` with the
   resolved writer pre-injected: `runner.run(adapter, input) { ... }` — for hand-rolled entry
   points that aren't observation-instrumented. Sugar over (1); evaluate once (1) exists.
3. Consider whether sampling should be reachable from non-HTTP units too (the filter consults
   a `CanonicalLogSampler`; scheduled jobs get none). Probably out of scope — jobs are
   low-volume — but note it.

## Acceptance

The HTTP filter and the scheduling handler emit through **one** shared `CanonicalLineWriter`
that a user bean overrides for both; no starter re-hardcodes the logstash emit; existing HTTP
filter behaviour and its user-bean-override test unchanged.
