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

```json
{
  "logger_name": "canonical",
  "http_request_method": "GET",
  "http_route": "/posts/1",
  "http_response_status_code": 200,
  "http_request_duration_ms": 21,
  "work_unit_id": "69eab700-480c-4611-af6d-6b7f4592e113",
  "work_unit_kind": "http",
  "db_query_count": 2,
  "db_query_duration_ms_total": 3,
  "http_client_request_count": 2,
  "http_client_request_duration_ms_total": 7,
  "post_id": 1,
  "comment_count": 7
}
```

| Source | Fields |
| --- | --- |
| `HttpWorkUnitAdapter` | `http_request_method`, `http_route`, `http_response_status_code`, `http_request_duration_ms`, `work_unit_id`, `work_unit_kind` |
| `JdbcCanonicalListener` | `db_query_count`, `db_query_duration_ms_total`, `db_slow_query_count`, `db_query_error_count` |
| `OkHttpCanonicalInterceptor` | `http_client_request_count`, `http_client_request_duration_ms_total`, `http_client_4xx_count`, `http_client_5xx_count`, `http_client_error_count` |
| Handler code via `CanonicalLog.put` / `.markFailed` / `.markDegraded` | Whatever you want |

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

## Tech

- Kotlin 2.2.20, JDK 25 (toolchain), JVM 21 bytecode (broad runtime compatibility)
- Spring Boot 4.0.6, Spring Framework 7.0
- Gradle 9.5.0
