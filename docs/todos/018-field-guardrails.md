# Guardrails on field count and value size

**Status:** todo · **Modules:** `canonical-log-core`

## Problem

Nothing bounds the accumulator: a `put` inside a per-item loop mints unbounded keys; a huge
string value makes the line explode downstream (Loki per-line limits, log pipeline costs).
Telemetry-never-breaks also means telemetry-never-DoSes-the-pipeline.

## Design (sketch)

- Follow the established marker pattern (`canonical_log_type_conflict` from commit
  `aa34166`): on breach, drop/truncate and record — never throw.
  - Max distinct fields (e.g. 256): further new keys dropped,
    `canonical_log_fields_dropped=<count>` recorded.
  - Max value length for CharSequences (e.g. 8 KiB): truncate,
    `canonical_log_truncated_keys` marker (bounded list or count — mind the guardrail not
    violating itself).
- Limits configurable at `CanonicalLogContext` construction (entry points pass through;
  starter could expose properties later — keep core constructor-based first).
- Costs one comparison per put on the hot path — keep it branch-cheap; no regex, no
  serialization-time measuring.
- Decide interaction with `increment` (Long fields can't breach size; count cap applies).

## Acceptance

Property-style test: N puts over the cap → map size capped, drop marker correct, no throw;
truncation preserves prefix + marker; snapshot/emit unaffected otherwise; docs gotcha entry.
