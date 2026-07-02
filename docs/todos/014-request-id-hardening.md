# Harden client-supplied X-Request-Id

**Status:** todo · **Modules:** `canonical-log-spring-boot-starter`

## Problem

`HttpWorkUnitAdapter.describe` uses `request.getHeader("X-Request-Id")` verbatim as
`work_unit_id`. Values pass through the JSON encoder (so no log injection), but a hostile or
buggy client can send an arbitrarily large or garbage value that becomes the line's identity —
and, once 006 lands, an MDC value on every log line of the request.

## Design (sketch)

- Validate + truncate: accept up to N chars (e.g. 128) of a safe charset
  (`[A-Za-z0-9._-]`, decide exactly); otherwise fall back to `UUID.randomUUID()` (current
  no-header behaviour). Consider `x_request_id_rejected=true` marker when falling back so
  operators can see misbehaving clients.
- Make the header name configurable later only if asked (`canonical-log.http.request-id-header`);
  don't pre-build it.

## Acceptance

Adapter tests: normal header passes through; oversized/invalid header → UUID fallback +
marker field; absent header unchanged.
