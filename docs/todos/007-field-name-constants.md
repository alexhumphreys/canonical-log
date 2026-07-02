# Publish well-known field names as constants

**Status:** todo · **Modules:** `canonical-log-core` (constants), contributors (adoption)

## Problem

All keys are strings; a typo mints a new field silently, and there's no single place listing
the canonical vocabulary (`db_query_count`, `http_route`, ...) for handler authors or query
authors. Also undocumented: precedence — `enrich` runs after the block, so adapter fields
silently overwrite same-key handler `put`s.

## Design (agreed direction)

- `CanonicalFields` object in core with `const val` for every field the library itself writes:
  the `error`/`error_reason`/`error_class`/`degraded`* markers, `canonical_log_*` markers,
  `work_unit_id`/`work_unit_kind`, and the contributor fields (`db_*`, `http_*`,
  `http_client_*`). Grouped + KDoc'd with type and semantics (the KDoc on
  `JdbcCanonicalListener` already has good per-field prose to lift).
- Contributors and `HttpWorkUnitAdapter` reference the constants instead of string literals.
- Document precedence explicitly (KDoc on `WorkUnitAdapter.enrich` + docs/CLAUDE.md): adapter
  wins over handler for the same key; handler-set `error_reason` is the deliberate exception
  (adapter checks before writing).
- Anti-goal guardrail: this is a constants file, NOT a field registry/schema DSL —
  `docs/CLAUDE.md` anti-goals explicitly reject that.

## Acceptance

No behaviour change (pure refactor + docs); a test asserting the constants match the strings
the contributors actually emit (or simply the refactor making that tautological); docs table
of fields → constants for query authors (README already has a sourced-field table to extend).
