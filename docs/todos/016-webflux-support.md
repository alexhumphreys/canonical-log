# WebFlux / reactive entry point

**Status:** todo (larger; demand-driven) · **Modules:** new or extended spring starter
**Depends on:** 020 — the reactive filter must emit through the shared `CanonicalLineWriter`
(in core after 020, default bean via `canonical-log-logstash`), not its own hardcoded logstash
emit. Building this before 020 would recreate the duplicated-sink friction 019 documented for
the scheduling starter.

## Problem

The starter is servlet-only (`@ConditionalOnWebApplication(type = SERVLET)`). Reactive stacks
are the visible adoption gap. The hard part — context propagation across coroutines — is
already solved in core; a reactive `WebFilter` is mostly lifecycle plumbing. Already listed as
a v0.2 candidate in docs/CLAUDE.md; this file adds the review's framing.

## Design (sketch)

- `WebFilter` that opens the context, stores it in Reactor's `Context` (subscriber context),
  and emits on terminal signal (`doFinally`) — the reactive analogue of the AsyncListener
  path, including exactly-once emit discipline.
- For coroutine-based WebFlux handlers, `CanonicalLogElement` should mostly Just Work once the
  element is in the `CoroutineContext`; the work is bridging Reactor `Context` ↔ threadlocal
  for non-coroutine operators (Micrometer's context-propagation library may do the heavy
  lifting — evaluate before hand-rolling).
- Contributors are already framework-agnostic; JDBC is irrelevant on reactive (R2DBC would be
  a new contributor — out of scope), OkHttp interceptor works wherever the context resolves.

## Acceptance

Reactive sample endpoint with one canonical line per request under concurrent load, zero
field bleeding (mirror the existing `ab`-based verification for servlet).
