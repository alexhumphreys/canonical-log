# canonlog

[![CI](https://github.com/alexhumphreys/canonlog/actions/workflows/ci.yml/badge.svg)](https://github.com/alexhumphreys/canonlog/actions/workflows/ci.yml)

Stripe-style [canonical log lines](https://stripe.com/blog/canonical-log-lines) for JVM services. One wide, structured log line per unit of work, contributed to mechanically by HTTP, JDBC, and OkHttp instrumentation, augmented explicitly by handler code via a tiny API.

> Status: **0.1, experimental.** Expect breaking changes.

## What it is

- A Kotlin library on top of SLF4J + Logback, packaged as Spring Boot starters.
- A pattern, not a framework: contributors mechanically add fields to a per-request accumulator; the line is emitted at request end.
- Coroutine- and virtual-thread-aware out of the box.

## What it isn't

- Not a logging framework — Logback still handles transport, formatting, and output.
- Not auto-instrumentation — contributors are explicit Gradle dependencies.
- Not OpenTelemetry — coexists fine, has no opinion on traces or spans.

## Quickstart

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.canonlog:canonlog-spring-boot-starter:0.1.0-SNAPSHOT")
}
```

In a handler, contribute business fields:

```kotlin
@GetMapping("/posts/{id}")
fun getPost(@PathVariable id: Long): Post {
    CanonicalLog.put("post_id", id)
    val post = repo.findById(id) ?: run {
        CanonicalLog.markFailed("post_not_found", "post_id" to id)
        throw ResponseStatusException(NOT_FOUND)
    }
    return post
}
```

You'll get one log line per request with HTTP / DB / OkHttp fields contributed automatically, plus your `post_id` and (if applicable) `error_reason`.

## What gets in the canonical log

Three real example lines from the [sample app](samples/spring-demo/), one per outcome shape. Each is one line of JSON; pretty-printed here for readability.

### 1. Success — `GET /posts/1` → 200

```json
{
  "@timestamp": "2026-05-02T07:28:55.321316Z",
  "logger_name": "canonical",
  "service_name": "canonlog-spring-demo",
  "environment": "local",

  "http_request_method": "GET",
  "http_route": "/posts/1",
  "http_response_status_code": 200,
  "http_request_duration_ms": 16,
  "work_unit_id": "9049d2c5-cd75-417c-89ed-44ae7665b670",
  "work_unit_kind": "http",

  "db_query_count": 2,
  "db_query_duration_ms_total": 0,

  "http_client_request_count": 2,
  "http_client_request_duration_ms_total": 1,

  "post_id": 1,
  "tag_count": 3,
  "comment_count": 7,
  "cache_hit": false
}
```

### 2. Marked failure — `GET /posts/999` → 404

The handler called `CanonicalLog.markFailed("post_not_found", "post_id" to id)` before throwing `ResponseStatusException(NOT_FOUND)`. The `error=true` and `error_reason` are handler intent. The absence of `error_class` is the signal that this was a deliberately-flagged failure rather than an uncaught exception — useful for queries that want to distinguish business errors from unexpected ones.

```json
{
  "@timestamp": "2026-05-02T07:28:55.347233Z",
  "logger_name": "canonical",

  "http_request_method": "GET",
  "http_route": "/posts/999",
  "http_response_status_code": 404,
  "http_request_duration_ms": 7,
  "work_unit_id": "bff8ee89-02d1-46c0-83c1-216ff256b1ce",
  "work_unit_kind": "http",

  "error": true,
  "error_reason": "post_not_found",

  "db_query_count": 1,
  "db_query_duration_ms_total": 0,
  "post_id": 999
}
```

### 3. Thrown failure — `GET /posts/1/explode` → 500

The handler threw an unhandled `RuntimeException`. The bridge's `Outcome.Threw` path causes the adapter to populate `error_class` (the exception's qualified name) and a default `error_reason="exception"`. The presence of `error_class` is the signal that this was an uncaught throw, not a `markFailed` call.

```json
{
  "@timestamp": "2026-05-02T07:28:55.380509Z",
  "logger_name": "canonical",

  "http_request_method": "GET",
  "http_route": "/posts/1/explode",
  "http_response_status_code": 500,
  "http_request_duration_ms": 2,
  "work_unit_id": "03ea7c82-6764-4c50-9f2f-5689a42f9507",
  "work_unit_kind": "http",

  "error": true,
  "error_reason": "exception",
  "error_class": "jakarta.servlet.ServletException",

  "post_id": 1
}
```

### Where each field comes from

| Source | Fields |
| --- | --- |
| `HttpWorkUnitAdapter` (umbrella starter) | `http_request_method`, `http_route`, `http_response_status_code`, `http_request_duration_ms`, `work_unit_id`, `work_unit_kind`, `error_class` (on `Threw`), `error_reason` (default if handler didn't set one) |
| `JdbcCanonicalListener` (jdbc starter) | `db_query_count`, `db_query_duration_ms_total`, `db_slow_query_count`, `db_query_error_count` |
| `OkHttpCanonicalInterceptor` (okhttp starter) | `http_client_request_count`, `http_client_request_duration_ms_total`, `http_client_4xx_count`, `http_client_5xx_count`, `http_client_error_count` |
| Handler code via `CanonicalLog.put` / `.markFailed` / `.markDegraded` | `post_id`, `tag_count`, `comment_count`, `cache_hit`, `error_reason` (handler intent) — anything you want |
| Logstash encoder + `customFields` | `@timestamp` (UTC), `service_name`, `environment` |

### Querying tip

`error=true` is set by both the marked-failure path and the thrown-failure path. To distinguish them: a thrown failure has `error_class`, a marked failure has only `error_reason`. Convention for query authors: `error="true"` matches both, `error="true" AND _exists_:error_class` filters to thrown, `error="true" AND NOT _exists_:error_class` filters to handler-flagged business failures. The "absent means false" rule applies — `error` is omitted on success rather than emitted as `false`.

## Outcome model

`Outcome` reports lifecycle: `Completed(durationMs)` if the block returned, `Threw(durationMs, cause)` if it threw. Whether the work was *semantically* successful is up to the handler:

- `CanonicalLog.markFailed(reason, ...fields)` → sets `error=true`, `error_reason=<reason>`. For typed errors (Either, Result), business-rule violations, or 4xx responses returned cleanly.
- `CanonicalLog.markDegraded(reason, ...fields)` → sets `degraded=true` without flagging error. For partial successes, cache fallbacks, etc.

The HTTP adapter defers to handler-set `error_reason` and only injects defaults (`exception` for `Threw`, `server_error` for 5xx) when nothing is set.

## Sample

See [`samples/spring-demo`](samples/spring-demo/README.md) — runs end-to-end on `localhost:8080` with H2 + an in-process MockWebServer for outbound calls.

## Modules

- `canonlog-core` — accumulator, SPI, no framework deps
- `canonlog-okhttp` / `canonlog-jdbc` — contributor instrumentation
- `canonlog-okhttp-spring-boot-starter` / `canonlog-jdbc-spring-boot-starter` — per-contributor auto-config
- `canonlog-spring-boot-starter` — umbrella: HTTP filter + transitive contributor starters

## Roadmap (deferred from v0.1)

- Kafka contributor
- Retry / circuit breaker contributor
- LaunchDarkly contributor
- WebFlux/Reactor support
- First-class integration with [Arrow](https://arrow-kt.io/) `Either` and Kotlin's [rich errors (KEEP-0441)](https://github.com/Kotlin/KEEP/issues/441) once stable
- Sampling
- Sensitive value redaction / header filtering
- Configuration DSL
- Cross-service propagation
- Maven Central publishing

The v0.1 POC is for validating the kernel; everything else lands as feedback informs design.

## Testing

The kernel's invariants are pinned with property tests rather than worked examples: random concurrent contributions against the accumulator, arbitrary nested coroutine structures (`withContext` / `async` / `coroutineScope` / bare `async`) against the bridge, randomized servlet async-listener callback orderings against the filter, and full-stack random plans through a real Spring Boot app on both virtual and platform threads.

## License

[Apache License 2.0](LICENSE).
