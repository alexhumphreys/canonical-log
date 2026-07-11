# canonical-log-servlet

Framework-neutral servlet HTTP integration for any `jakarta.servlet` stack (plain Tomcat,
Jetty, Jersey-on-servlet) — the hard-won request lifecycle without Spring. Zero Spring
dependencies; `compileOnly` servlet-api 5.0.0 (the lowest `jakarta.*` API, so it loads on
EE9 and EE10+ containers).

Most adopters use a wrapper instead: [Spring starter](../canonical-log-spring-boot-starter/README.md)
or [Dropwizard bundle](../canonical-log-dropwizard/README.md), both thin callers of this
module.

## What's here

- `runCanonicalHttpRequest(...)` — the **single copy** of the request lifecycle: open →
  filter chain → sync/async emit split → unbind. Handles async dispatch (suspend
  controllers, `Callable`/`DeferredResult`, SSE), where `chain.doFilter` returns before the
  handler completes — emit is deferred to an `AsyncListener` with an emit-exactly-once
  guard. Change async behaviour here, not per-filter. (`@DelicateCanonicalLogApi`.)
- `HttpWorkUnitAdapter` + `HttpExchange` — the status/error/499-cancellation heuristics.
  Route lookup is a constructor param `routeResolver`, defaulting to the
  `HttpWorkUnitAdapter.ROUTE_ATTRIBUTE` request attribute: any glue that publishes the
  matched route there gets `http_route` for free.
- `CanonicalLogServletFilter` — a plain `HttpFilter` caller. The writer has no default
  (deployment-specific — see the [sink chooser](../docs/sinks.md)); an `includeRequest`
  predicate and a once-per-request guard are built in.
- `PathExclusions.matcher(...)` — two-rule (exact / trailing-`*` prefix) path excluder.

Fields written: see [docs/fields.md](../docs/fields.md) (inbound HTTP section).
