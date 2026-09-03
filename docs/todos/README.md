# TODO queue

Each file is a self-contained work item (014–019 from the 2026-07-02 design review; 020–025
from the 2026-07-04 framework-portability review): problem, agreed design (or the decision
still to make), files to touch, acceptance criteria. They're written so a fresh Claude Code
session can implement one from just that file plus the sources it names — the intended
workflow is one item per session (`/clear` between items), e.g.:

> implement docs/todos/001-injectable-http-adapter-and-line-writer.md

When an item lands, delete its file and update this index (and any `docs/CLAUDE.md` sections
the file says to update). If implementation diverges from the file's design, record why in
`docs/CLAUDE.md`'s decisions section.

## Priority: agreed next up (in order)

The framework-portability track (make the library adoptable beyond Spring Boot, no behaviour
changes to existing adopters):

1. [022](022-canonical-log-dropwizard.md) — Dropwizard bundle + Jersey route capture

(020 — seam types to core, `canonical-log-logstash` split, MDC writer — landed 2026-07-04,
superseding 017 and delivering 019. 025 — Java 17 bytecode floor for library modules
(`-Xjdk-release=17`/`options.release(17)`, toolchain stays 25) — landed 2026-07-04; Spring
Boot 4's 17 baseline meant no starter exception was needed, so the new servlet/Dropwizard
modules are born on 17. 021 — `canonical-log-servlet`, the framework-neutral servlet module —
landed 2026-07-04: `runCanonicalHttpRequest` is now the single copy of the async-aware HTTP
lifecycle both filters call, `HttpExchange`/`HttpWorkUnitAdapter` moved there (with route
lookup as a `routeResolver` ctor param + `ROUTE_ATTRIBUTE`), plus `CanonicalLogServletFilter`
and `PathExclusions`; deprecated typealiases remain in `...canonicallog.spring`. Unblocks 022.)

[023](023-adapter-seed-hook.md) and [024](024-graduate-open-close-and-consumer-recipe.md)
are independent and can be slotted anywhere.

After the track: [026](026-canonical-log-kafka.md) (Kafka) and
[027](027-trace-correlation-contributor.md) (trace correlation) are the natural next picks —
both are long-standing v0.2 candidates from docs/CLAUDE.md, now spec'd — followed by
[030](030-canonical-log-sqs.md) (SQS adapter, small, sits on 026's constants) and
[031](031-canonical-log-jobrunr.md) (JobRunr background jobs).
[028](028-json-canonical-line-writer.md) is small and slots anywhere;
[029](029-canonical-log-grpc.md) is demand-driven — leave it until a gRPC adopter
materializes.

## Backlog

| # | Item | One-liner |
|---|------|-----------|
| [014](014-request-id-hardening.md) | X-Request-Id hardening | Validate/truncate client-supplied work-unit ids |
| [015](015-time-section-helper.md) | `CanonicalLog.time { }` | Section timing helper (`_ms_total` + `_count`) |
| [016](016-webflux-support.md) | WebFlux support | Reactive `WebFilter`; core bridge already coroutine-ready |
| [018](018-field-guardrails.md) | Field guardrails | Cap field count / value size with truncation markers |
| [022](022-canonical-log-dropwizard.md) | `canonical-log-dropwizard` | `ConfiguredBundle` wiring servlet filter + Jersey route-template capture + MDC writer default |
| [023](023-adapter-seed-hook.md) | `WorkUnitAdapter.seed` | Capture ambient context (trace ids, MDC) at open time on the opening thread |
| [024](024-graduate-open-close-and-consumer-recipe.md) | Open/close graduation + consumer recipe | Stabilize `openCanonicalWorkUnit`/`CanonicalWorkUnitScope`; broker-agnostic message-consumer recipe with pinned examples |
| [026](026-canonical-log-kafka.md) | `canonical-log-kafka` | Consumer work-unit adapter + capture-at-send producer decorator (v0.2 candidate, spec'd) |
| [027](027-trace-correlation-contributor.md) | Trace correlation | `OtelSeedingAdapter` + core `MdcSeedingAdapter` — `trace_id`/`span_id` via the seed hook |
| [028](028-json-canonical-line-writer.md) | `JsonCanonicalLineWriter` | Dependency-free typed JSON sink in core (`canonicalLineJson` serializer + writer) |
| [029](029-canonical-log-grpc.md) | `canonical-log-grpc` | Server interceptor entry point + client contributor (demand-driven) |
| [030](030-canonical-log-sqs.md) | `canonical-log-sqs` | `SqsMessageWorkUnitAdapter` for poll-loop consumers (adapter only; loop stays recipe) |
| [031](031-canonical-log-jobrunr.md) | `canonical-log-jobrunr` | `JobServerFilter`-based transparent work units per job processing attempt |
| [035](035-virtual-thread-torture-and-leak-soak.md) | Virtual-thread torture + leak soak | 100k VT units w/ pinning, weak-ref reachability, carrier residue (JDK 21+ only) |
| [036](036-emit-exactly-once-race-hammer.md) | Emit-exactly-once hammer | Barrier-concurrent terminal callbacks on the async listener + consumer-recipe ack/nack |
| [039](039-concurrent-emit-output-integrity.md) | Concurrent-emit output integrity | Parse-back every line from concurrent writers; adversarial values; late-increment cutoff |
| [041](041-kafka-adapter-hidden-record-docs.md) | Kafka hidden-record docs | Doc-only: KDoc + recipe pointer for frameworks that hide `ConsumerRecord` |
| [042](042-workunitadapter-of-factory.md) | `WorkUnitAdapter.of` | Lambda factory for one-off adapters (companion object + `of(describe, seed, enrich)`) |
| [045](045-workunitscope-idempotence-guards.md) | Scope idempotence guards | CAS-guard `emit`/`unbind` inside `CanonicalWorkUnitScope` (WARN, not corrupt) |
| [048](048-contributor-contract-harness.md) | Contributor contract harness | Reusable no-throw/no-leak/type-convention contract every contributor runs (promotes the deferred `ContributorContractTest` + negative-assertion helpers) |

Dependencies: 022 depends on 021 (landed); 023 and 024 are independent (023 and 018 touch the
same core files — either order, second one rebases). 016's shared-writer dependency (020) has
landed. 014 (X-Request-Id hardening) now targets `canonical-log-servlet`'s `HttpWorkUnitAdapter`,
where the adapter moved.
026 depends on 024 (it updates the recipe and its pinned test); 027 hard-depends on
023 (it is the seed hook's first shipped use); 029 builds on 024's graduated open/close
primitive; 030 depends on 026 (shared `MESSAGING_*` constants) and 024 (recipe pointer);
031 stands alone (reads better after 024, else `@OptIn` like the scheduling starter).
041 and 042 (from the 2026-07-11 Dropwizard-integration dogfooding feedback) are independent of
everything; 041 is doc-only and 042's recipe update reads best after 041's rewording (either
order works, second one rebases the recipe wording).
044, 046, 047 and 049 (from the design-explainer gap reviews — see PR #28) **landed
2026-09-03**: 047 as `CanonicalFields.MESSAGE`; 046 as `CanonicalLogContext.get`/`contains`
(with the check-before-default sweep across every shipped adapter and a `get`/`contains`
operation added to the Lincheck spec — `snapshot()` is now called only at emit time and in
tests); and 044 + 049 together as one diagnostic surface, since 049's own file asked to reuse
044's hook rather than add a second one — `CanonicalLog.onUnboundContribution` (Option A's core
hook, read only on the already-unbound branch, so no cost when bound) and
`CanonicalLog.onLateWrite` alongside a `canonical_log_late_write_count` counter and a
once-per-unit WARN, wrapped for adopters as `canonical-log-test`'s `failOnUnboundContributions`
/ `failOnLateWrites` / `failOnLostContributions` / `recordLostContributions`. See the
diagnostics entry in `docs/CLAUDE.md` for the decisions, including the two deviations from the
files as written (the counter is `_count`-suffixed per the naming convention, and 044's
fallback `strict` flag was unnecessary — the hook costs a *bound* contribution nothing, which
`AllocationBudgetTest` confirms). `docs/design-explainer.md` §3.2, §4.6 and §5 were updated in
step, as those files' explainer notes required.

Still open from that batch: [045](045-workunitscope-idempotence-guards.md) (independent, and
its tests read best on top of 049's late-write counter) and
[048](048-contributor-contract-harness.md), which reads best now that 044's hook exists and can
reuse it. The reactive/CompletableFuture-chain propagation gap the same review re-surfaced is
already tracked as [016](016-webflux-support.md).

**Each file carries a `**Model:**` line** with a recommended model and the reason, continuing
the convention the 033–040 batch used. The split is by *kind* of work, not difficulty: Sonnet 5
for the mechanically-specified ones, where the file already fixes the design and implementation
is transcription (045); Fable 5.1 for open-ended design, where the failure mode is a wrong
abstraction rather than a bug (048). Those two are what remain of the batch.
043 — `canonical-log-resilience4j` — landed 2026-08-23: `CanonicalResilience4j.register(registry)`
attaches to each Resilience4j registry via its `EventPublisher` (plus `onEntryAdded`, so
lazily-created instances are covered), contributing `retry_*`, the four `*_rejected_count`
families, and the `resilience_rejected` flag; `canonical-log-resilience4j-spring-boot-starter`
does it automatically for every registry bean. The Spring-side half followed immediately as
`canonical-log-spring-retry-spring-boot-starter`, covering both Spring Framework 7's built-in
`@Retryable` (via `MethodRetryEvent`) and classic `org.springframework.retry` (via a
`RetryListener` bean) and reusing the same `retry_*` constants — see the per-stack
attempt-counting decision in `docs/CLAUDE.md`.
033–040 are the concurrency-bulletproofing track (2026-07-10): all independent except 035
(reuses 034's assertion shape), 036 §3 and 040 §2 (optional Lincheck hooks from 033), and
040 (most valuable last, since it measures the others). 033 (Lincheck, landed 2026-07-10 as
`CanonicalLogContextLincheckTest`), 034 (landed 2026-07-10 as `DataBleedStormTest`), and 037
(landed 2026-07-10 as `HostilePlanPropertyTest` — hostile nodes + three-way must/mustNot/may
oracle; no undefined semantics surfaced, every composition matched the documented contracts)
are done. Suggested order for the rest: 040 (036/039/035 landed; 038 landed 2026-07-10 as `LifecycleReentrancyTest` — emit-finalized + seed-bound alignment on the suspend path, contracts in `EmitFn`/`WorkUnitAdapter` KDoc). Each file carries a recommended model (Sonnet 5 for the
mechanically-specified ones: 034/035/036/039/040; Opus 4.8 or Fable for the ones needing
semantics judgment: 033/037). (001–004, which other items depended on, landed 2026-07-02; 006 — MDC `work_unit_id` — landed 2026-07-03 as the `CanonicalLogMdc` mirror, opt-out `canonical-log.http.mdc-enabled`; 007 — field-name constants — landed 2026-07-03 as `CanonicalFields` with adapter-wins precedence documented on `WorkUnitAdapter.enrich`; 008 — OkHttp `enqueue()` — landed 2026-07-03 as tag-first resolution in the interceptor plus the `Request.Builder.withCanonicalContext()` opt-in helper; 009 — second entry point — landed 2026-07-03 as the `@Scheduled` `ReportingJob` sample (scope 1), then reworked 2026-07-04 into the transparent `canonical-log-scheduling-spring-boot-starter` (observation-based, no wrapping), spawning follow-up 019 for the shared-writer friction; 012 — config metadata — landed 2026-07-04 as hand-written `spring-configuration-metadata.json` per starter, guarded by `ConfigurationMetadataTest`; 013 — human-readable message — landed 2026-07-04 as `canonicalLineMessage` in core, shared by both emit sites; 010 — nested work-unit semantics — landed 2026-07-02 as "inner shadows outer" with `parent_work_unit_id` + `work_unit_depth`; 011 — cancellation semantics — landed 2026-07-02 as `Outcome.Cancelled` with `cancelled=true`/`cancel_reason`, CE always rethrown.)
