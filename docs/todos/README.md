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

1. [021](021-canonical-log-servlet.md) — framework-neutral servlet module
2. [022](022-canonical-log-dropwizard.md) — Dropwizard bundle + Jersey route capture

(020 — seam types to core, `canonical-log-logstash` split, MDC writer — landed 2026-07-04,
superseding 017 and delivering 019. 025 — Java 17 bytecode floor for library modules
(`-Xjdk-release=17`/`options.release(17)`, toolchain stays 25) — landed 2026-07-04; Spring
Boot 4's 17 baseline meant no starter exception was needed, so the new servlet/Dropwizard
modules are born on 17.)

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
| [021](021-canonical-log-servlet.md) | `canonical-log-servlet` | Extract the async-aware HTTP lifecycle + `HttpWorkUnitAdapter` into a Spring-free servlet module |
| [022](022-canonical-log-dropwizard.md) | `canonical-log-dropwizard` | `ConfiguredBundle` wiring servlet filter + Jersey route-template capture + MDC writer default |
| [023](023-adapter-seed-hook.md) | `WorkUnitAdapter.seed` | Capture ambient context (trace ids, MDC) at open time on the opening thread |
| [024](024-graduate-open-close-and-consumer-recipe.md) | Open/close graduation + consumer recipe | Stabilize `openCanonicalWorkUnit`/`CanonicalWorkUnitScope`; broker-agnostic message-consumer recipe with pinned examples |
| [026](026-canonical-log-kafka.md) | `canonical-log-kafka` | Consumer work-unit adapter + capture-at-send producer decorator (v0.2 candidate, spec'd) |
| [027](027-trace-correlation-contributor.md) | Trace correlation | `OtelSeedingAdapter` + core `MdcSeedingAdapter` — `trace_id`/`span_id` via the seed hook |
| [028](028-json-canonical-line-writer.md) | `JsonCanonicalLineWriter` | Dependency-free typed JSON sink in core (`canonicalLineJson` serializer + writer) |
| [029](029-canonical-log-grpc.md) | `canonical-log-grpc` | Server interceptor entry point + client contributor (demand-driven) |
| [030](030-canonical-log-sqs.md) | `canonical-log-sqs` | `SqsMessageWorkUnitAdapter` for poll-loop consumers (adapter only; loop stays recipe) |
| [031](031-canonical-log-jobrunr.md) | `canonical-log-jobrunr` | `JobServerFilter`-based transparent work units per job processing attempt |

Dependencies: 021 → 022 (in that order); 023 and 024 are independent (023 and 018 touch the
same core files — either order, second one rebases). 016's shared-writer dependency (020) has
landed. 014 is best done after 021 (the adapter it hardens moves to `canonical-log-servlet`).
026 depends on 024 (it updates the recipe and its pinned test); 027 hard-depends on
023 (it is the seed hook's first shipped use); 029 builds on 024's graduated open/close
primitive; 030 depends on 026 (shared `MESSAGING_*` constants) and 024 (recipe pointer);
031 stands alone (reads better after 024, else `@OptIn` like the scheduling starter). (001–004, which other items depended on, landed 2026-07-02; 006 — MDC `work_unit_id` — landed 2026-07-03 as the `CanonicalLogMdc` mirror, opt-out `canonical-log.http.mdc-enabled`; 007 — field-name constants — landed 2026-07-03 as `CanonicalFields` with adapter-wins precedence documented on `WorkUnitAdapter.enrich`; 008 — OkHttp `enqueue()` — landed 2026-07-03 as tag-first resolution in the interceptor plus the `Request.Builder.withCanonicalContext()` opt-in helper; 009 — second entry point — landed 2026-07-03 as the `@Scheduled` `ReportingJob` sample (scope 1), then reworked 2026-07-04 into the transparent `canonical-log-scheduling-spring-boot-starter` (observation-based, no wrapping), spawning follow-up 019 for the shared-writer friction; 012 — config metadata — landed 2026-07-04 as hand-written `spring-configuration-metadata.json` per starter, guarded by `ConfigurationMetadataTest`; 013 — human-readable message — landed 2026-07-04 as `canonicalLineMessage` in core, shared by both emit sites; 010 — nested work-unit semantics — landed 2026-07-02 as "inner shadows outer" with `parent_work_unit_id` + `work_unit_depth`; 011 — cancellation semantics — landed 2026-07-02 as `Outcome.Cancelled` with `cancelled=true`/`cancel_reason`, CE always rethrown.)
