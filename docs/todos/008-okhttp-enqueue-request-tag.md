# OkHttp enqueue() support via request tag

**Status:** todo · **Modules:** `canonical-log-okhttp` (+ starter docs)

## Problem

`OkHttpCanonicalInterceptor` resolves the work unit via the calling thread, so `Call.execute()`
works but `Call.enqueue()` runs interceptors on OkHttp's dispatcher threads and every field is
silently dropped. Documented as a dead-end on the interceptor KDoc (commit `043d892`) — but a
tag can cross where the threadlocal can't.

## Design (agreed direction)

- Interceptor resolves the context in order: `chain.request().tag(CanonicalLogContext::class.java)`
  first, threadlocal fallback. Contribution goes through the resolved context directly (needs
  small refactor: the interceptor currently uses the ambient `CanonicalLog` object; switch to
  resolving a `CanonicalLogContext?` once, then `ctx?.increment(...)`).
- Opt-in for async callers:
  `Request.Builder().tag(CanonicalLogContext::class.java, currentCanonicalContext())`.
  Provide a tiny helper extension in the module, e.g.
  `fun Request.Builder.withCanonicalContext(context: CanonicalLogContext? = currentCanonicalContext())`.
- Optional sugar: a `Call.Factory` wrapper that tags every request automatically at call
  creation time (creation happens on the caller's thread, so capture is correct there) —
  evaluate whether it earns its API surface.
- Note: `currentCanonicalContext()` is public API; the tag approach needs no `@OptIn`.
- Update the interceptor KDoc paragraph that currently declares enqueue unsupported; update
  `docs/CLAUDE.md` gotcha ("Plain thread hops...") which references it.

## Acceptance

- Test with MockWebServer: `enqueue()` from inside a work unit with a tagged request →
  `http_client_request_count` lands in the emitted snapshot; untagged enqueue still a no-op;
  `execute()` path unchanged (existing tests).
