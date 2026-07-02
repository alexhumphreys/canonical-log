# Define nested work-unit semantics

**Status:** todo (design before code) · **Modules:** `canonical-log-core`, docs

## Problem

Nesting is "undefined" today, and the blocking and suspend variants already behave differently
(blocking restores the previous threadlocal correctly; suspend delegates to the
`ThreadContextElement` default merge — innermost wins, outer resumes after). Message-batch
processing (outer batch unit, inner per-message units) will hit this early; an accident will
become a de-facto contract. `docs/CLAUDE.md` "Open semantics questions" and the gotchas list
both flag it.

## Design (recommended contract from the 2026-07-02 review)

**"Inner shadows outer"** as the v1 contract: while an inner work unit is open, ambient
contributions route to the inner accumulator only; when it closes, the outer resumes. Each
unit emits its own line. No automatic parent/child linkage in v1 — but consider one cheap,
high-value field: inner lines carry `parent_work_unit_id` when opened inside an outer unit.

Work items:
- Make blocking and suspend variants match the contract (the blocking variant effectively
  already does; the suspend variant needs a pinned decision rather than incidental
  `ThreadContextElement` behaviour — see the `CopyableThreadContextElement` migration note in
  docs/CLAUDE.md v0.2 candidates: only needed if per-child copy semantics are required).
- Delete the docs/CLAUDE.md instruction "Do not write tests that pin this" and pin it.
- Decide `parent_work_unit_id`: in or out (leaning in).
- Explicitly not in scope: aggregating child fields into the parent line.

## Acceptance

Contract tests for: contributions inside inner land on inner only; outer resumes after inner
closes; both entry-point variants behave identically; two lines emitted in the nested case;
docs updated (gotchas + open-questions sections rewritten as defined semantics).
