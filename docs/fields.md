# Field reference

Every field the library writes, its type, who writes it, and when it's present. Field names
the library owns are published as `const val`s on `CanonicalFields` (`canonical-log-core`) —
reference the constant from handler code and query builders so a rename is a compile error.
This page is guarded by `canonical-log-core:CanonicalFieldsDocTest`: every constant's value
must appear here.

Handlers may `put` any key they like; this reference covers only the library-written
vocabulary. (The `messaging_*` fields are deliberate string literals, not constants — see
[Messaging](#messaging-canonical-log-kafka-consumer-canonical-log-sqs).)

## Conventions

- **snake_case**, OpenTelemetry semantic conventions with dot→underscore mapping
  (`http.request.method` → `http_request_method`) for Loki compatibility.
- **Durations** are integer milliseconds: `_ms` for a single duration,
  `_duration_ms_total` for a sum. Sub-ms work reports `0` — by design.
- **Counts** are `Long` with a `_count` suffix.
- **Booleans are omitted when false** ("absent means false"): query `error="true"`, never
  `error!="false"`.
- **Null values are omitted**, never emitted as `field=null`. Lists and nested objects are
  never emitted — decompose to counts or delimited strings.
- **Write order for a key is `seed` → handler → `enrich`** (adapter `enrich` wins). The two
  "intent" fields — `error_reason`, `cancel_reason` — are the exception: adapters check
  before writing their default, so a handler-set value survives.
- **Thrown vs marked failure**: a thrown failure has `error_class`; a marked failure
  (`markFailed`) has only `error_reason`. `error="true"` matches both;
  `AND _exists_:error_class` filters to thrown.

## Work-unit identity (core entry points / adapters)

| Field | Type | Written by | Present |
| --- | --- | --- | --- |
| `work_unit_id` | String | adapter `describe` | always; also mirrored into slf4j MDC under the same name for debug-log correlation |
| `work_unit_kind` | String | adapter `describe` | always (`http`, `scheduled_job`, `background_job`, `kafka_message`, ...) |
| `parent_work_unit_id` | String | core entry points | only on nested units — the *immediate* parent's id |
| `work_unit_depth` | Long | core entry points | only on nested units (1 = inside a top-level unit); absent = top-level |

## Outcome markers (handler intent; adapter defaults)

| Field | Type | Written by | Present |
| --- | --- | --- | --- |
| `error` | Boolean | `markFailed`; HTTP adapter on 5xx / thrown | on failure only (absent = success) |
| `error_reason` | String | handler via `markFailed` wins; adapter default `exception` / `server_error` | whenever `error=true` |
| `error_class` | String | adapter, thrown outcome only | FQCN of the throwable; presence = uncaught throw |
| `degraded` | Boolean | `markDegraded` | partial success; does **not** set `error` |
| `degraded_reason` | String | `markDegraded` | alongside `degraded` |
| `cancelled` | Boolean | adapter on `Outcome.Cancelled` | cancellation is not an error — no `error=true` |
| `cancel_reason` | String | handler value wins; defaults `cancelled` / `async_timeout` | alongside `cancelled` |

## Inbound HTTP (`canonical-log-servlet` `HttpWorkUnitAdapter`, via the Spring starter / Dropwizard bundle)

| Field | Type | Present |
| --- | --- | --- |
| `http_request_method` | String | always |
| `url_path` | String | always — the actual path; high-cardinality, don't group on it |
| `http_route` | String | only when a route template matched (`/posts/{id}`) — group on this |
| `http_response_status_code` | Long | always; heuristically corrected (late 5xx → 500, cancelled → 499) |
| `http_request_duration_ms` | Long | always |
| `x_request_id_rejected` | Boolean | only when a client-supplied request-id header was rejected (too long / bad charset) |

## Database (`canonical-log-jdbc`)

| Field | Type | Notes |
| --- | --- | --- |
| `db_query_count` | Long | statements — a batch of N counts as N |
| `db_execution_count` | Long | round-trips — a batch counts as 1 |
| `db_execution_duration_ms_total` | Long | pair with `db_execution_count` (not `db_query_count`) for mean per-round-trip latency |
| `db_slow_execution_count` | Long | executions at/over the slow threshold |
| `db_execution_error_count` | Long | failed executions |

## Outbound HTTP client (`canonical-log-okhttp`)

| Field | Type | Notes |
| --- | --- | --- |
| `http_client_request_count` | Long | one per user-issued call; transparent retries/redirects don't count |
| `http_client_request_duration_ms_total` | Long | total time in `chain.proceed()` |
| `http_client_4xx_count` | Long | calls that got a 4xx |
| `http_client_5xx_count` | Long | calls that got a 5xx |
| `http_client_error_count` | Long | calls that failed with no response (connect/DNS/timeout) |

## Kafka producer (`canonical-log-kafka`, `withCanonicalLogging()` decorator)

| Field | Type | Notes |
| --- | --- | --- |
| `kafka_produce_count` | Long | counted at `send` submit |
| `kafka_produce_duration_ms_total` | Long | submit → ack; acks landing after emit are cut off |
| `kafka_produce_error_count` | Long | acks that completed exceptionally |

## Resilience (`canonical-log-resilience4j`, `canonical-log-spring-retry-spring-boot-starter`)

Per-work-unit attribution for the Resilience4j layer around outbound calls. Resilience4j's own
Micrometer metrics answer the global question ("how often is this breaker open?"); these answer
the per-request one ("did *this* request retry, or get shed?"). Run both.

| Field | Type | Notes |
| --- | --- | --- |
| `retry_attempt_count` | Long | retried attempts only — the initial call isn't counted; also written by the Spring retry contributors |
| `retry_exhausted_count` | Long | the retry gave up and rethrew; also written by the Spring retry contributors |
| `retry_wait_duration_ms_total` | Long | backoff waited, summed; splits "slow" into waiting vs calling. Resilience4j only — neither Spring retry stack exposes the interval to its listener |
| `circuit_breaker_rejected_count` | Long | calls an open breaker refused (never attempted) |
| `circuit_breaker_failure_count` | Long | calls the breaker recorded as failures (attempted, failed) |
| `circuit_breaker_open_name` | String | which breaker shed the call; last rejection wins |
| `bulkhead_rejected_count` | Long | semaphore or thread-pool bulkhead was full |
| `rate_limiter_rejected_count` | Long | the limiter did not permit the call |
| `time_limiter_timeout_count` | Long | the time limiter fired |
| `resilience_rejected` | Boolean | **any** of the above rejections/timeouts hit this unit |

`resilience_rejected="true"` is the field to lead a dashboard with: it separates "we refused to
call" from "the call failed", which otherwise both surface as `error="true"`. A shed unit also
carries **no** `http_client_*` fields — the call never left the process.

The counters are deliberately instance-name-free: a name belongs in a field *value*
(`circuit_breaker_open_name`), never in a field name, or every new `Retry` mints new columns.
Per-name breakdown of the counters is a non-goal here — that's what the Micrometer tags are for.

The two `retry_*` fields are deliberately shared with
`canonical-log-spring-retry-spring-boot-starter`, which writes them for Spring Framework's
built-in `@Retryable` and for classic Spring Retry: the constants name the *concept*, so one
query answers "did this request retry?" whichever library did the retrying. The rejection
families below `retry_*` are Resilience4j-only — Spring's stacks have no equivalent seam
(`@ConcurrencyLimit` throws without publishing anything observable).

## Jobs (`canonical-log-jobrunr`, scheduling starter)

| Field | Type | Written by | Notes |
| --- | --- | --- | --- |
| `job_name` | String | both job adapters | low-cardinality logical name (`ReportingJob.generateReport`) |
| `job_id` | String | JobRunr adapter | the runner's own id, joins to its dashboard |
| `job_attempt` | Long | JobRunr adapter | 1-based processing attempt |
| `job_duration_ms` | Long | both job adapters | wall-clock attempt duration |

`work_unit_kind` is `scheduled_job` for `@Scheduled` ticks and `background_job` for JobRunr
attempts — deliberately distinct so they're queryably different.

## Trace correlation (`canonical-log-tracing-otel` / core `MdcSeedingAdapter`)

| Field | Type | Present |
| --- | --- | --- |
| `trace_id` | String | only when a valid span / MDC id is active at work-unit *open* (captured in `seed`, never core) |
| `span_id` | String | same capture story as `trace_id` |

## Messaging (`canonical-log-kafka` consumer, `canonical-log-sqs`)

These are deliberate **string literals** in the adapters, not `CanonicalFields` constants
(no messaging contributor exists in core; the modules keep the same wire values by
convention — see the field-constants gotcha in `docs/CLAUDE.md`).

| Field | Type | Notes |
| --- | --- | --- |
| `messaging_system` | String | `kafka` / `aws_sqs` |
| `messaging_destination_name` | String | topic / queue name |
| `messaging_kafka_partition` | Long | Kafka only |
| `messaging_kafka_offset` | Long | Kafka only |
| `messaging_message_id` | String | when resolvable |
| `messaging_process_duration_ms` | Long | per-message processing time |
| `messaging_sqs_receive_count` | Long | SQS only; requires requesting the `ApproximateReceiveCount` system attribute |

## Library self-diagnostics (`canonical_log_*`)

Telemetry never fails the operation it observes; failures are recorded on the line instead.

| Field | Type | Meaning |
| --- | --- | --- |
| `canonical_log_type_conflict` | Boolean | an `increment` hit a key holding a non-Long and was dropped |
| `canonical_log_type_conflict_key` | String | the colliding key (best-effort under concurrency; last conflict wins) |
| `canonical_log_type_conflict_type` | String | the type already stored there |
| `canonical_log_enrich_error` | Boolean | `WorkUnitAdapter.enrich` threw (swallowed + WARN'd) |
| `canonical_log_enrich_error_class` | String | FQCN of the enrich exception |
| `canonical_log_seed_error` | Boolean | `WorkUnitAdapter.seed` threw (swallowed + WARN'd) |
| `canonical_log_seed_error_class` | String | FQCN of the seed exception |
| `canonical_log_contributor_error` | Boolean | a contributor (interceptor/listener/event consumer) threw while contributing (swallowed) |
| `canonical_log_contributor_error_class` | String | FQCN of the contributor exception |
| `canonical_log_late_write_count` | Long | contributions that arrived after this unit's line was emitted (see below) |

`canonical_log_late_write_count` is the one marker you will **never see on a line**: by
definition it is written after the line was serialized. It exists so the loss is countable in a
test; the operator-facing signal is the single WARN the library logs per work unit, and the
test-facing one is `CanonicalLog.onLateWrite` (wrapped as `failOnLateWrites { }` in
`canonical-log-test`). Its sibling — an ambient contribution that found no bound unit at all —
writes nothing anywhere and is reported only through `CanonicalLog.onUnboundContribution`.

## Line composition

| Field | Type | Written by | When |
| --- | --- | --- | --- |
| `message` | String | `JsonCanonicalLineWriter` (folded into the object) | only when the snapshot doesn't already carry it — a handler-set value wins |

The human summary is composed by core's `canonicalLineMessage`; every value in it is also a
structured field — never parse it. The MDC and Logstash writers keep the summary as the slf4j
event *message* instead and write no `message` field. A handler that wants to own the summary
`put`s `CanonicalFields.MESSAGE` itself.

## Not written by the library

`@timestamp`, `service_name`, `environment` come from the logging encoder config (e.g.
logstash `customFields` — see the sample).
