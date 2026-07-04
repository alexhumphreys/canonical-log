# canonical-log

[![CI](https://github.com/alexhumphreys/canonical-log/actions/workflows/ci.yml/badge.svg)](https://github.com/alexhumphreys/canonical-log/actions/workflows/ci.yml)

Stripe-style [canonical log lines](https://stripe.com/blog/canonical-log-lines) for JVM services. One wide, structured log line per unit of work, contributed to mechanically by HTTP, JDBC, and OkHttp instrumentation, augmented explicitly by handler code via a tiny API.

> Status: **0.1, very experimental.** Mostly me playing with Claude Code.

## What it is

- A Kotlin library on top of SLF4J + Logback, packaged as Spring Boot starters.
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

You'll get one log line per request with HTTP / DB / OkHttp fields contributed automatically, plus your `post_id` and (if applicable) `error_reason`.

## Outbound HTTP wiring

The HTTP filter and JDBC starters auto-instrument transparently — they hook in at points Spring controls. Outbound HTTP is different: an `OkHttpClient` is configured at builder time, not bean-construction time, and trying to mutate one after construction either silently breaks anyone who holds a reference to the original or forces the starter to provide a default client (which conflicts with most apps' custom timeouts/dispatchers/caches).

Instead, the OkHttp starter provides an `OkHttpClientBuilderCustomizer` bean, which adopters apply where they build their client:

```kotlin
@Bean
fun okHttpClient(customizers: List<OkHttpClientBuilderCustomizer>): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // ... your config ...
    customizers.forEach { it.customize(builder) }
    return builder.build()
}
```

The list parameter composes cleanly: the canonical customizer ships under the bean name `canonicalOkHttpClientBuilderCustomizer`, and you can register additional `OkHttpClientBuilderCustomizer` beans (auth, logging, retries) without displacing it. Set `canonical-log.okhttp.enabled=false` to opt out entirely.

The same pattern works for `RestTemplateBuilder` / `WebClient.Builder` users — Spring's own builders use the same shape.

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

`url_path` is the actual path that was requested. `http_route` is the matched route template — it's what you group by in Loki/Grafana, because it's bounded in cardinality (every `/posts/N` request lands on the same route). The two together let a query say "all hits on the post-detail route" without conflating distinct posts.

### 2. Marked failure — `GET /posts/999` → 404

The handler called `CanonicalLog.markFailed("post_not_found", "post_id" to id)` before throwing `ResponseStatusException(NOT_FOUND)`. The `error=true` and `error_reason` are handler intent. The absence of `error_class` is the signal that this was a deliberately-flagged failure rather than an uncaught exception — useful for queries that want to distinguish business errors from unexpected ones.

```json
{
  "@timestamp": "2026-05-03T08:06:19.460885Z",
  "logger_name": "canonical",
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

The handler threw an unhandled `RuntimeException`. The bridge's `Outcome.Threw` path causes the adapter to populate `error_class` (the exception's qualified name) and a default `error_reason="exception"`. The presence of `error_class` is the signal that this was an uncaught throw, not a `markFailed` call.

```json
{
  "@timestamp": "2026-05-03T08:06:19.490255Z",
  "logger_name": "canonical",
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
| Core entry points (`withCanonicalLog` / `withCanonicalLogBlocking`) | `parent_work_unit_id`, `work_unit_depth` (only on work units opened inside another one — the immediate parent's `work_unit_id` and how deep the nesting goes; absent means top-level) |
| `HttpWorkUnitAdapter` (umbrella starter) | `http_request_method`, `url_path`, `http_route` (matched template, omitted if no route matched), `http_response_status_code`, `http_request_duration_ms`, `work_unit_id`, `work_unit_kind`, `error_class` (on `Threw`), `error_reason` (default if handler didn't set one), `cancelled` + `cancel_reason` (on `Cancelled`) |
| `JdbcCanonicalListener` (jdbc starter) | `db_query_count` (statements), `db_execution_count` (round-trips), `db_execution_duration_ms_total`, `db_slow_execution_count`, `db_execution_error_count` |
| `OkHttpCanonicalInterceptor` (okhttp starter, applied via `OkHttpClientBuilderCustomizer`) | `http_client_request_count`, `http_client_request_duration_ms_total`, `http_client_4xx_count`, `http_client_5xx_count`, `http_client_error_count` |
| Handler code via `CanonicalLog.put` / `.markFailed` / `.markDegraded` | `post_id`, `tag_count`, `comment_count`, `cache_hit`, `error_reason` (handler intent) — anything you want |
| Logstash encoder + `customFields` | `@timestamp` (UTC), `service_name`, `environment` |

Every field name the library writes is published as a `const` on `CanonicalFields` (in `canonical-log-core`), grouped and documented with its type and semantics. Reference the constant (`CanonicalFields.HTTP_ROUTE`, `CanonicalFields.DB_QUERY_COUNT`, ...) from handler code and query builders instead of a string literal, so a rename is a compile error rather than a silently-diverged dashboard. It's a constants file, not a schema — handlers can still `put` any key they like.

**Precedence.** Adapter `enrich` runs *after* the handler block, so for the same key the adapter's value wins. The reference adapter deliberately defers to a handler-set `error_reason` / `cancel_reason` (it checks before writing its own default); everything else it writes — status, durations, identity — overwrites. Follow the same check-before-default pattern in custom adapters for fields a handler is expected to own.

### Querying tip

`error=true` is set by both the marked-failure path and the thrown-failure path. To distinguish them: a thrown failure has `error_class`, a marked failure has only `error_reason`. Convention for query authors: `error="true"` matches both, `error="true" AND _exists_:error_class` filters to thrown, `error="true" AND NOT _exists_:error_class` filters to handler-flagged business failures. The "absent means false" rule applies — `error` is omitted on success rather than emitted as `false`.

The `message` field (e.g. `GET /posts/{id} 200 153ms`) is a human-readable summary for skimming a plain console — every value in it is also present as a structured field. Query the structured fields, never parse `message`.

### Correlating with debug logs

For the duration of a work unit the library mirrors `work_unit_id` into slf4j MDC, so every ordinary log line written while handling the request carries the same id as the canonical line. Jumping from a canonical line to the debug logs of that request (or back) is one equality query:

```
{service_name="canonical-log-sample"} | json | work_unit_id="97db3003-740b-460c-b458-d648111c56f6"
```

The logstash encoder includes MDC fields by default, so this works with the sample's `logback-spring.xml` as-is. The mirror follows the work unit everywhere contributions do: across coroutine dispatcher switches (no `MDCContext` pairing needed), onto executor threads via the `propagatingCanonicalContext()` wrappers, and to the innermost unit under nesting. Shops that already manage MDC themselves can opt out process-wide with `canonical-log.http.mdc-enabled=false` (or `CanonicalLogMdc.enabled = false` when not using the starter).

## Outcome model

`Outcome` reports lifecycle: `Completed(durationMs)` if the block returned, `Threw(durationMs, cause)` if it threw, `Cancelled(durationMs, cause)` if it was cut off by a `CancellationException` (client disconnect, request timeout, structured-concurrency cancellation). Whether the work was *semantically* successful is up to the handler:

- `CanonicalLog.markFailed(reason, ...fields)` → sets `error=true`, `error_reason=<reason>`. For typed errors (Either, Result), business-rule violations, or 4xx responses returned cleanly.
- `CanonicalLog.markDegraded(reason, ...fields)` → sets `degraded=true` without flagging error. For partial successes, cache fallbacks, etc.

The HTTP adapter defers to handler-set `error_reason` and only injects defaults (`exception` for `Threw`, `server_error` for 5xx) when nothing is set.

Cancellation is not a failure: a `Cancelled` line carries `cancelled=true` and a `cancel_reason` (`async_timeout` when the servlet async timeout fired, `cancelled` otherwise) instead of `error=true`, so timeouts and client disconnects don't pollute error rates. The HTTP adapter reports status `499` (nginx's "client closed request" convention) for cancelled requests unless an error status was already set. The `CancellationException` itself is always rethrown after the line is emitted — observing cancellation never breaks structured concurrency.

## Beyond HTTP: scheduled jobs (and other entry points)

The HTTP filter is just one entry point. The work-unit lifecycle generalizes to any unit of work — a Kafka consume, a `@Scheduled` job, a message handler — and everything downstream (the accumulator, the JDBC/OkHttp contributors, MDC correlation) works unchanged, because contributors resolve the active unit off the current thread, not off anything HTTP-specific.

For `@Scheduled` jobs there's a drop-in starter — add `canonical-log-scheduling-spring-boot-starter` to the classpath and your existing scheduled methods emit a canonical line each run, with **no change to the method body**:

```kotlin
@Component
class ReportingJob(private val jdbc: JdbcTemplate) {
    @Scheduled(fixedDelayString = "5000")
    fun generateReport() {
        val rows = jdbc.queryForObject("SELECT count(*) FROM posts", Long::class.java)
        CanonicalLog.put("report_row_count", rows) // db_query_count lands automatically
    }
}
```

Each run emits a line with `work_unit_kind=scheduled_job`, `job_name=ReportingJob.generateReport` (derived from the method), `job_duration_ms`, the JDBC contributor's `db_query_count`, and your `report_row_count` — no HTTP involved. It hooks Spring's own scheduled-task observation (not AOP), so it's transparent and needs only `@EnableScheduling` (no actuator). Override the job identity/fields with a `WorkUnitAdapter<ScheduledTaskObservationContext>` bean; opt out with `canonical-log.scheduling.enabled=false`.

For an entry point Spring doesn't instrument for you, open the unit explicitly with `withCanonicalLogBlocking(adapter, input, emit) { ... }` (or `withCanonicalLog` for suspend code) and a hand-written `WorkUnitAdapter` — that's all the scheduling starter does internally.

The sample demonstrates the scheduled path with [`ReportingJob`](samples/spring-demo/src/main/kotlin/io/github/alexhumphreys/canonicallog/sample/ReportingJob.kt); it's off by default (so it doesn't add background noise), enable with `--canonical-log.sample.scheduled-job.enabled=true`. Pinned by `ReportingJobEndToEndTest`.

## Sinks (writers)

The canonical line is emitted through a `CanonicalLineWriter`. Which one you want depends on how your logging backend renders structured fields — provide a bean/instance to override the default:

| Your layout | Writer | Where | Fields |
| --- | --- | --- | --- |
| logstash-logback-encoder configured | `LogstashCanonicalLineWriter` (default) | `canonical-log-logstash` | typed, via `StructuredArguments` |
| JSON layout that flattens MDC but not `StructuredArguments` (e.g. Dropwizard `EventJsonLayout`) | `MdcCanonicalLineWriter` | `canonical-log-core` | stringified (MDC is `String`-valued) |
| dedicated raw (`%msg%n`) appender / anything else | `JsonCanonicalLineWriter` | `canonical-log-core` | typed, message **is** a hand-rolled JSON object |

`JsonCanonicalLineWriter` is dependency-free (no encoder needed): the log event's message is the complete, typed JSON object, keys sorted, with the human summary folded in under `message`. Its `canonicalLineJson(fields)` serializer is public for custom sinks, and an `emitLine` lambda lets you send the string to a file/stdout/socket instead of slf4j. Never wire more than one writer for the same line.

## Sample

See [`samples/spring-demo`](samples/spring-demo/README.md) — runs end-to-end on `localhost:8080` with H2 + an in-process MockWebServer for outbound calls.

## Modules

- `canonical-log-core` — accumulator, SPI, no framework deps
- `canonical-log-okhttp` / `canonical-log-jdbc` — contributor instrumentation
- `canonical-log-okhttp-spring-boot-starter` / `canonical-log-jdbc-spring-boot-starter` — per-contributor auto-config
- `canonical-log-scheduling-spring-boot-starter` — transparent `@Scheduled` instrumentation via Spring's scheduled-task observation
- `canonical-log-spring-boot-starter` — umbrella: HTTP filter + transitive contributor starters

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
