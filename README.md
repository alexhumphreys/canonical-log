# canonical-log

[![CI](https://github.com/alexhumphreys/canonical-log/actions/workflows/ci.yml/badge.svg)](https://github.com/alexhumphreys/canonical-log/actions/workflows/ci.yml)

Stripe-style [canonical log lines](https://stripe.com/blog/canonical-log-lines) for JVM services. One wide, structured log line per unit of work, contributed to mechanically by HTTP, JDBC, and OkHttp instrumentation, augmented explicitly by handler code via a tiny API.

> Status: **0.1, very experimental.** Mostly me playing with Claude Code.

## What it is

- A Kotlin library on top of SLF4J + Logback, packaged as Spring Boot starters (plus a Dropwizard bundle and framework-neutral modules).
- A pattern, not a framework: contributors mechanically add fields to a per-request accumulator; the line is emitted at request end.
- Coroutine- and virtual-thread-aware out of the box.
- Requires **Java 17+**; built and tested on JDK 25 (the library modules ship Java 17 bytecode, and the suite also runs on a JDK 17 launcher in CI).

## What it isn't

- Not a logging framework — Logback still handles transport, formatting, and output.
- Not auto-instrumentation — contributors are explicit Gradle dependencies.
- Not OpenTelemetry — coexists fine, has no opinion on traces or spans.

## Quickstart

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.alexhumphreys:canonical-log-spring-boot-starter:0.1.0-SNAPSHOT")
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

You'll get one log line per request with HTTP / DB / OkHttp fields contributed automatically, plus your `post_id` and (if applicable) `error_reason`. Not on Spring? The [Dropwizard bundle](canonical-log-dropwizard/README.md) is one `addBundle`; any other `jakarta.servlet` stack can use [`canonical-log-servlet`](canonical-log-servlet/README.md) directly.

## What gets in the canonical log

Three real example lines from the [sample app](samples/spring-demo/), one per outcome shape. Each is one line of JSON; pretty-printed here for readability.

### 1. Success — `GET /posts/1` → 200

```json
{
  "@timestamp": "2026-05-03T08:06:19.437881Z",
  "logger_name": "canonical",
  "message": "GET /posts/{id} 200 153ms",
  "service_name": "canonical-log-spring-demo",
  "environment": "local",

  "http_request_method": "GET",
  "url_path": "/posts/1",
  "http_route": "/posts/{id}",
  "http_response_status_code": 200,
  "http_request_duration_ms": 153,
  "work_unit_id": "97db3003-740b-460c-b458-d648111c56f6",
  "work_unit_kind": "http",

  "db_query_count": 2,
  "db_execution_count": 2,
  "db_execution_duration_ms_total": 3,

  "http_client_request_count": 2,
  "http_client_request_duration_ms_total": 24,

  "post_id": 1,
  "tag_count": 3,
  "comment_count": 7,
  "cache_hit": false
}
```

`url_path` is the actual path requested; `http_route` is the matched template — bounded in cardinality, so it's what you group by in Loki/Grafana.

### 2. Marked failure — `GET /posts/999` → 404

The handler called `CanonicalLog.markFailed("post_not_found", "post_id" to id)` before throwing. The **absence of `error_class`** is the signal that this was a deliberately-flagged business failure rather than an uncaught exception.

```json
{
  "message": "GET /posts/{id} 404 3ms error=post_not_found",
  "http_request_method": "GET",
  "url_path": "/posts/999",
  "http_route": "/posts/{id}",
  "http_response_status_code": 404,
  "http_request_duration_ms": 3,
  "work_unit_id": "f37110bf-4a09-4617-ac13-401f1b20b96f",
  "work_unit_kind": "http",

  "error": true,
  "error_reason": "post_not_found",

  "db_query_count": 1,
  "db_execution_count": 1,
  "db_execution_duration_ms_total": 0,
  "post_id": 999
}
```

### 3. Thrown failure — `GET /posts/1/explode` → 500

An unhandled exception. The **presence of `error_class`** is the signal of an uncaught throw:

```json
{
  "message": "GET /posts/{id}/explode 500 2ms error=exception",
  "http_request_method": "GET",
  "url_path": "/posts/1/explode",
  "http_route": "/posts/{id}/explode",
  "http_response_status_code": 500,
  "http_request_duration_ms": 2,
  "work_unit_id": "0b67b2cc-0635-4b6e-a31b-352673584898",
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
| Core entry points | `parent_work_unit_id`, `work_unit_depth` (only on nested work units) |
| HTTP adapter (Spring starter / Dropwizard bundle) | `http_request_method`, `url_path`, `http_route`, `http_response_status_code`, `http_request_duration_ms`, `work_unit_id`, `work_unit_kind`, error/cancellation markers |
| [JDBC contributor](canonical-log-jdbc/README.md) | `db_query_count`, `db_execution_count`, `db_execution_duration_ms_total`, `db_slow_execution_count`, `db_execution_error_count` |
| [OkHttp contributor](canonical-log-okhttp/README.md) | `http_client_request_count`, `http_client_request_duration_ms_total`, `http_client_4xx_count`, `http_client_5xx_count`, `http_client_error_count` |
| [Trace seeding adapters](canonical-log-tracing-otel/README.md) (opt-in) | `trace_id`, `span_id` |
| Handler code via `CanonicalLog.put` / `.markFailed` / `.markDegraded` | `post_id`, `cache_hit`, `error_reason` — anything you want |
| Logstash encoder `customFields` | `@timestamp`, `service_name`, `environment` |

**[docs/fields.md](docs/fields.md)** is the full field reference — every field's type, writer, and presence rules, plus the naming conventions. Field names are published as constants on `CanonicalFields`; reference those from handler code and query builders so a rename is a compile error, not a silently-diverged dashboard.

### Querying tips

- `error="true"` matches both failure shapes; add `_exists_:error_class` for thrown, `NOT _exists_:error_class` for marked business failures. Booleans are omitted when false — query `error="true"`, never `error!="false"`.
- `message` is a human summary for skimming; every value in it is also a structured field. Never parse it.
- `work_unit_id` is mirrored into MDC for the duration of the work unit, so every ordinary debug log line carries the same id as the canonical line — jumping between them is one equality query.

## Outcome model

The library reports lifecycle facts (`Outcome.Completed` / `Threw` / `Cancelled`); the handler expresses intent:

- `CanonicalLog.markFailed(reason, ...fields)` → `error=true`, `error_reason` — for typed errors, business-rule violations, 4xx responses returned cleanly.
- `CanonicalLog.markDegraded(reason, ...fields)` → `degraded=true` without flagging error — partial successes, cache fallbacks.

Adapters defer to handler-set `error_reason` and only inject defaults (`exception`, `server_error`) when nothing is set. Cancellation (client disconnect, timeout) is **not** an error: the line carries `cancelled=true` + `cancel_reason` and HTTP status 499, so timeouts don't pollute error rates; the `CancellationException` is always rethrown.

## Beyond HTTP

The work-unit lifecycle generalizes to any entry point — contributors resolve the active unit off the current thread, not off anything HTTP-specific:

- **`@Scheduled` jobs**: [scheduling starter](canonical-log-scheduling-spring-boot-starter/README.md) — a line per run, no change to the method body.
- **Kafka**: [consumer adapter + producer decorator](canonical-log-kafka/README.md).
- **SQS**: [message adapter](canonical-log-sqs/README.md).
- **JobRunr**: [a line per processing attempt](canonical-log-jobrunr/README.md).
- **Anything else**: open the unit yourself with `withCanonicalLogBlocking` / `withCanonicalLog`, or the `openCanonicalWorkUnit` open/close pair for split-callback listeners — **[docs/recipes/message-consumers.md](docs/recipes/message-consumers.md)** is the worked, broker-agnostic recipe (compiled and pinned by `MessageConsumerRecipeTest`).

## Sinks

The line is emitted through a `CanonicalLineWriter`: logstash `StructuredArguments` (default), MDC-flattening, or dependency-free JSON — see **[docs/sinks.md](docs/sinks.md)** for the chooser and custom-sink/sampling seams.

## Modules

Each module README has the wiring snippet, the fields it writes, and its gotchas.

| Module | What |
| --- | --- |
| [`canonical-log-core`](canonical-log-core/README.md) | accumulator, lifecycle, SPI, coroutine bridge — no framework deps |
| [`canonical-log-spring-boot-starter`](canonical-log-spring-boot-starter/README.md) | umbrella: HTTP filter + transitive contributor starters |
| [`canonical-log-jdbc`](canonical-log-jdbc/README.md) / [starter](canonical-log-jdbc-spring-boot-starter/README.md) | `db_*` contributor |
| [`canonical-log-okhttp`](canonical-log-okhttp/README.md) / [starter](canonical-log-okhttp-spring-boot-starter/README.md) | `http_client_*` contributor (starter needs one wiring step) |
| [`canonical-log-scheduling-spring-boot-starter`](canonical-log-scheduling-spring-boot-starter/README.md) | transparent `@Scheduled` instrumentation |
| [`canonical-log-servlet`](canonical-log-servlet/README.md) | framework-neutral servlet lifecycle |
| [`canonical-log-dropwizard`](canonical-log-dropwizard/README.md) | Dropwizard 4 bundle |
| [`canonical-log-logstash`](canonical-log-logstash/README.md) | default typed sink |
| [`canonical-log-tracing-otel`](canonical-log-tracing-otel/README.md) | `trace_id`/`span_id` seeding |
| [`canonical-log-kafka`](canonical-log-kafka/README.md) | consumer adapter + producer decorator |
| [`canonical-log-sqs`](canonical-log-sqs/README.md) | SQS message adapter |
| [`canonical-log-jobrunr`](canonical-log-jobrunr/README.md) | JobRunr background jobs |
| [`canonical-log-test`](canonical-log-test/README.md) | adopter test kit |

## Sample

See [`samples/spring-demo`](samples/spring-demo/README.md) — runs end-to-end on `localhost:8080` with H2 + an in-process MockWebServer for outbound calls.

## Roadmap (deferred from v0.1)

Retry/circuit-breaker contributor, LaunchDarkly contributor, WebFlux/Reactor support, Arrow `Either` / rich-errors integration, sampling policies, redaction hooks, configuration DSL, cross-service propagation, Maven Central publishing. The v0.1 POC is for validating the kernel; everything else lands as feedback informs design.

## Testing

The kernel's invariants are pinned with property tests rather than worked examples: random concurrent contributions against the accumulator, arbitrary nested coroutine structures against the bridge, Lincheck model-checking of the accumulator, randomized servlet async-listener orderings against the filter, and full-stack random plans through a real Spring Boot app on both virtual and platform threads. See the Testing section of [docs/CLAUDE.md](docs/CLAUDE.md).

## License

[Apache License 2.0](LICENSE).
