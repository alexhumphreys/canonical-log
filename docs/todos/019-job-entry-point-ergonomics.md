# Non-HTTP entry-point ergonomics (injectable writer / job runner helper)

**Status:** todo · **Modules:** `canonical-log-spring-boot-starter` (+ maybe core)

## Problem

Surfaced while building the second entry point (todo 009, the `@Scheduled` `ReportingJob`
sample). `WorkUnitAdapter` + `withCanonicalLogBlocking` generalize past HTTP cleanly, but a
job author hand-rolls one thing the HTTP filter gets for free: **the emit sink**.

`CanonicalLogAutoConfiguration` resolves a `CanonicalLineWriter` (user-bean override, logstash
default) via `ObjectProvider` and injects it into the filter — but registers no
`CanonicalLineWriter` **bean**. So a non-HTTP entry point has nothing to inject and constructs
`LogstashCanonicalLineWriter()` directly (see `ReportingJob`). That means:

- a job doesn't pick up a user's custom `CanonicalLineWriter` bean the way the filter does —
  two sinks can silently diverge;
- the logstash logger name (`"canonical"`) is hard-coded at the job's construction site.

Non-issues (verified, don't "fix"): MDC correlation is already free — `withCanonicalLogBlocking`
installs `work_unit_id` into MDC itself. describe/enrich boilerplate is the intended model, not
friction.

## Design (sketch — pick one, demand-driven)

1. **Expose the resolved writer as a bean.** Make the auto-config register the
   `CanonicalLineWriter` (default logstash, `@ConditionalOnMissingBean` so a user bean wins),
   and have the filter inject the bean instead of `getIfAvailable { ... }`. Then any entry
   point injects the same sink. Smallest change; watch the CoMB-vs-ObjectProvider reasoning in
   the existing filter bean (writer isn't generic, so plain CoMB is fine for it).
2. **A `CanonicalWorkUnitRunner` helper bean** that wraps `withCanonicalLogBlocking` with the
   resolved writer pre-injected: `runner.run(adapter, input) { ... }`. Sugar over (1); evaluate
   whether it earns its surface once (1) exists.
3. Consider whether sampling should be reachable from non-HTTP units too (the filter consults
   a `CanonicalLogSampler`; jobs get none). Probably out of scope — jobs are low-volume — but
   note it.

## Acceptance

Non-HTTP entry point injects the same `CanonicalLineWriter` a user bean would override, without
constructing `LogstashCanonicalLineWriter()` by hand; existing HTTP filter behaviour and its
user-bean-override test unchanged.
