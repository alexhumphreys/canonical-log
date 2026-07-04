# TODO queue

Each file is a self-contained work item from the 2026-07-02 design review: problem, agreed
design (or the decision still to make), files to touch, acceptance criteria. They're written
so a fresh Claude Code session can implement one from just that file plus the sources it
names — the intended workflow is one item per session (`/clear` between items), e.g.:

> implement docs/todos/001-injectable-http-adapter-and-line-writer.md

When an item lands, delete its file and update this index (and any `docs/CLAUDE.md` sections
the file says to update). If implementation diverges from the file's design, record why in
`docs/CLAUDE.md`'s decisions section.

## Priority: agreed next up (in order)

*(empty — pick from the backlog)*

## Backlog

| # | Item | One-liner |
|---|------|-----------|
| [014](014-request-id-hardening.md) | X-Request-Id hardening | Validate/truncate client-supplied work-unit ids |
| [015](015-time-section-helper.md) | `CanonicalLog.time { }` | Section timing helper (`_ms_total` + `_count`) |
| [016](016-webflux-support.md) | WebFlux support | Reactive `WebFilter`; core bridge already coroutine-ready |
| [017](017-logstash-writer-module-split.md) | Logstash writer split | Stop forcing logstash-logback-encoder on every adopter |
| [018](018-field-guardrails.md) | Field guardrails | Cap field count / value size with truncation markers |
| [019](019-job-entry-point-ergonomics.md) | Non-HTTP entry-point ergonomics | Injectable writer / job-runner helper (friction from 009) |

Dependencies: none currently. (001–004, which other items depended on, landed 2026-07-02; 006 — MDC `work_unit_id` — landed 2026-07-03 as the `CanonicalLogMdc` mirror, opt-out `canonical-log.http.mdc-enabled`; 007 — field-name constants — landed 2026-07-03 as `CanonicalFields` with adapter-wins precedence documented on `WorkUnitAdapter.enrich`; 008 — OkHttp `enqueue()` — landed 2026-07-03 as tag-first resolution in the interceptor plus the `Request.Builder.withCanonicalContext()` opt-in helper; 009 — second entry point — landed 2026-07-03 as the `@Scheduled` `ReportingJob` sample (scope 1), then reworked 2026-07-04 into the transparent `canonical-log-scheduling-spring-boot-starter` (observation-based, no wrapping), spawning follow-up 019 for the shared-writer friction; 012 — config metadata — landed 2026-07-04 as hand-written `spring-configuration-metadata.json` per starter, guarded by `ConfigurationMetadataTest`; 013 — human-readable message — landed 2026-07-04 as `canonicalLineMessage` in core, shared by both emit sites; 010 — nested work-unit semantics — landed 2026-07-02 as "inner shadows outer" with `parent_work_unit_id` + `work_unit_depth`; 011 — cancellation semantics — landed 2026-07-02 as `Outcome.Cancelled` with `cancelled=true`/`cancel_reason`, CE always rethrown.)
