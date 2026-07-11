# canonical-log-spring-boot-starter

The umbrella starter: one dependency gives a Spring Boot (servlet) app one canonical line
per HTTP request, with the JDBC and OkHttp contributor starters pulled in transitively.

```kotlin
dependencies {
    implementation("io.github.alexhumphreys:canonical-log-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

Contribute business fields from handlers with `CanonicalLog.put(...)` /
`CanonicalLog.markFailed(...)` — see the [top-level README](../README.md) for the quickstart
and example lines, and [docs/fields.md](../docs/fields.md) for what the filter writes
(`http_request_method`, `url_path`, `http_route`, `http_response_status_code`,
`http_request_duration_ms`, work-unit identity, error/cancellation markers).

## How it works

`CanonicalLogFilter` (a `OncePerRequestFilter`) opens the work unit and delegates the whole
lifecycle to `runCanonicalHttpRequest` from [`canonical-log-servlet`](../canonical-log-servlet/README.md),
including the async-dispatch handling suspend controllers need. Route templates come from
Spring MVC's best-matching-pattern attribute. `work_unit_id` is mirrored into MDC for the
duration of the request (opt out: `canonical-log.http.mdc-enabled=false`).

## Configuration

| Property | Default | Effect |
| --- | --- | --- |
| `canonical-log.http.exclude-paths` | (none) | ant-style patterns matched against the request URI; matches skip the work unit entirely (probes cost nothing) |
| `canonical-log.http.mdc-enabled` | `true` | process-wide `work_unit_id` MDC mirror |
| `canonical-log.jdbc.enabled` / `canonical-log.okhttp.enabled` | `true` | contributor opt-outs |

## Customization seams (beans)

- `WorkUnitAdapter<HttpExchange>` — replaces the default HTTP adapter. Compose with
  `HttpWorkUnitAdapter(springRouteResolver)`, don't subclass: delegate `describe`/`enrich`,
  then `put` your extra fields. A bare `HttpWorkUnitAdapter()` would lose `http_route`.
- `CanonicalLineWriter` — replaces the default `LogstashCanonicalLineWriter`
  ([sink chooser](../docs/sinks.md)). One bean overrides the sink for HTTP **and**
  scheduled-job lines.
- `CanonicalLogSampler` — emit predicate, consulted after enrich (sees status/duration/error);
  default emits everything. Throwing samplers fail open.

Telemetry failures (enrich/sampler/writer) are WARN-logged and never fail the request.

## Gotchas

- Suspend controllers need `kotlin-reflect` and `kotlinx-coroutines-reactor` on the
  classpath, or Spring invokes the suspend method reflectively and NPEs on the missing
  `Continuation`.
- On an uncaught exception the container maps to 500 *after* the filter unwinds; the adapter
  compensates by reporting 500 when `Outcome.Threw` and status < 500 (a custom 503 mapper
  will still show as 500 — known approximation).
