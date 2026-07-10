# Harden client-supplied X-Request-Id

**Status:** todo · **Modules:** `canonical-log-spring-boot-starter` (until 021 lands — see below)
**Ordering:** prefer doing this **after** 021. That item moves `HttpWorkUnitAdapter` into the
new `canonical-log-servlet` module, so the hardening then lands framework-neutrally and covers
the Dropwizard/Jersey integration (022) too — the audience where a hostile header matters just
as much. Once 021 has landed: the module is `canonical-log-servlet`, and the adapter tests
this file describes live there (plain servlet fakes, not Spring test utilities). If this is
implemented first instead, the change simply moves along with 021's extraction.

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
