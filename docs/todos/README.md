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

| # | Item | One-liner |
|---|------|-----------|
| [003](003-test-kit-module.md) | `canonical-log-test` module | `captureCanonicalLine { }` harness so adopters can assert on lines |

## Backlog

| # | Item | One-liner |
|---|------|-----------|
| [004](004-non-throwing-emit.md) | Non-throwing emit | Sink failure must not replace the block's result (consistency with `aa34166`) |
| [005](005-java-ergonomics.md) | Java ergonomics | `@JvmStatic`/`@JvmOverloads`/Map overloads; Java smoke test |
| [006](006-mdc-work-unit-id.md) | MDC `work_unit_id` | Correlate canonical line with ordinary logs |
| [007](007-field-name-constants.md) | Field-name constants | `CanonicalFields`; document adapter-wins precedence |
| [008](008-okhttp-enqueue-request-tag.md) | OkHttp `enqueue()` via request tag | Turn the documented dead-end into an opt-in path |
| [009](009-second-entry-point-adapter.md) | Second entry-point sample | Kafka/@Scheduled adapter to validate the abstraction |
| [010](010-nested-work-unit-semantics.md) | Nested work-unit semantics | Define "inner shadows outer"; align blocking/suspend |
| [011](011-cancellation-semantics.md) | Cancellation semantics | `Outcome.Cancelled`, `cancelled=true` not `error=true` |
| [012](012-config-metadata.md) | Config metadata | IDE autocomplete for `canonical-log.*` properties |
| [013](013-human-readable-message.md) | Human-readable message | `GET /posts/{id} 200 12ms` instead of literal `"canonical"` |
| [014](014-request-id-hardening.md) | X-Request-Id hardening | Validate/truncate client-supplied work-unit ids |
| [015](015-time-section-helper.md) | `CanonicalLog.time { }` | Section timing helper (`_ms_total` + `_count`) |
| [016](016-webflux-support.md) | WebFlux support | Reactive `WebFilter`; core bridge already coroutine-ready |
| [017](017-logstash-writer-module-split.md) | Logstash writer split | Stop forcing logstash-logback-encoder on every adopter |
| [018](018-field-guardrails.md) | Field guardrails | Cap field count / value size with truncation markers |

Dependencies: 010 and 011 are design-first items. (001 and 002, which other items depended on, landed 2026-07-02.)
