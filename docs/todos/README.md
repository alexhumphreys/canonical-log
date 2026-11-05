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

## Priority: agreed next up

The 044–049 batch from the design-explainer gap reviews has fully landed. 041 and 042 (from the 2026-07-11 Dropwizard-integration dogfooding feedback) are small and
slot anywhere. 015, 016 and 029 are demand-driven — leave them until an adopter asks
(029 in particular waits for a gRPC adopter).

## Backlog

| # | Item | One-liner |
|---|------|-----------|
| [015](015-time-section-helper.md) | `CanonicalLog.time { }` | Section timing helper (`_ms_total` + `_count`) |
| [016](016-webflux-support.md) | WebFlux support | Reactive `WebFilter`; core bridge already coroutine-ready |
| [029](029-canonical-log-grpc.md) | `canonical-log-grpc` | Server interceptor entry point + client contributor (demand-driven) |
| [041](041-kafka-adapter-hidden-record-docs.md) | Kafka hidden-record docs | Doc-only: KDoc + recipe pointer for frameworks that hide `ConsumerRecord` (the Kafka README already has it) |
| [042](042-workunitadapter-of-factory.md) | `WorkUnitAdapter.of` | Lambda factory for one-off adapters (companion object + `of(describe, seed, enrich)`) |

Dependencies: 016's shared-writer dependency (020) has landed; 029 builds on the graduated
open/close primitive (024, landed). 041 and 042 are independent; 042's recipe update reads
best after 041's rewording (either order works, the second one rebases the recipe wording).
Keep `docs/design-explainer.md` in sync if an item changes something that document describes.

**Each file carries a `**Model:**` line** with a recommended model and the reason. The split is
by *kind* of work, not difficulty: Sonnet for the mechanically-specified ones, where the file
already fixes the design and implementation is transcription (045/049); Opus where a decision
outlives the item — a public API surface or a concurrency spec (044); Fable for open-ended
design, where the failure mode is a wrong abstraction rather than a bug (048).

## Landed

Everything numbered 001–049 and not listed above has landed or been superseded (017 by 020); `git log --grep 'todo NNN'`
finds the commit, and `docs/CLAUDE.md` records the resulting decisions. Most recently: 014
(X-Request-Id hardening, in `canonical-log-servlet`), 022 (`canonical-log-dropwizard`), 023
(`WorkUnitAdapter.seed`), 026 (`canonical-log-kafka`), 027 (trace correlation), 028
(`JsonCanonicalLineWriter`), 030 (`canonical-log-sqs`), 031 (`canonical-log-jobrunr`), 043
(Resilience4j + Spring retry), 018 (field guardrails), 046/047 (`CanonicalLogContext.get`/`contains`,
`CanonicalFields.MESSAGE`), 044/045/049 (contribution diagnostics + scope idempotence
guards), and 048 (contributor/adapter contract harness + negative assertions).

`docs/CLAUDE.md` and the `benchmarks` module call the allocation-budget/JMH work "todo 044";
that is a different item from the strict-mode 044 — the number was reused.
