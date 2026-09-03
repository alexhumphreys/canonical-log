package io.github.alexhumphreys.canonicallog

/**
 * The canonical vocabulary: every field name the library itself writes, in one place.
 *
 * Keys in the accumulator are plain strings, so a typo mints a silent new field. These
 * constants are the single source of truth the contributors and adapters reference instead
 * of string literals — reference them from handler code and queries too, so a rename is a
 * compile error rather than a silently-diverged dashboard.
 *
 * **Scope: this is a constants file, not a schema.** There is deliberately no registry,
 * no type metadata, no validation DSL — those are operator/Detekt concerns and an explicit
 * anti-goal (see `docs/CLAUDE.md`). Handlers are free to `put` any key they like; these are
 * just the ones the library guarantees.
 *
 * **Precedence.** Adapter `enrich` runs *after* the handler block, so for the same key the
 * adapter's value wins. The library's adapters deliberately defer to a handler-set value for
 * the two "intent" fields — [ERROR_REASON] and [CANCEL_REASON] — by checking whether the key
 * is already present ([CanonicalLogContext.get] / [CanonicalLogContext.contains]) before
 * writing their own default. Everything else the adapter writes
 * (status, durations, counts) overwrites. See [WorkUnitAdapter.enrich].
 *
 * Naming/type conventions (from `docs/CLAUDE.md`): snake_case, integer-ms durations with
 * `_ms` / `_duration_ms_total` suffixes, `_count` for counters, booleans omitted when false.
 */
public object CanonicalFields {

    // --- Semantic outcome markers (handler intent; some defaulted by the adapter) ---

    /**
     * `Boolean` — set to `true` by [CanonicalLog.markFailed], and by the HTTP adapter for
     * 5xx responses / thrown exceptions. Omitted (not `false`) on success: query authors
     * must test `error="true"`, never `error!="false"`.
     */
    public const val ERROR: String = "error"

    /**
     * `String` — why the unit failed. A handler-set value (via `markFailed`) wins; the
     * adapter only fills a default (`"exception"`, `"server_error"`) when the handler
     * didn't set one. [ERROR_REASON] present *without* [ERROR_CLASS] is the signal of a
     * marked business failure rather than an uncaught exception.
     */
    public const val ERROR_REASON: String = "error_reason"

    /**
     * `String` — fully-qualified class name of the throwable, written by the adapter on a
     * thrown outcome only. Its presence distinguishes a thrown failure from a marked one.
     */
    public const val ERROR_CLASS: String = "error_class"

    /** `Boolean` — set by [CanonicalLog.markDegraded]: succeeded, but with caveats. Does not set [ERROR]. */
    public const val DEGRADED: String = "degraded"

    /** `String` — why the unit was degraded, set alongside [DEGRADED] by `markDegraded`. */
    public const val DEGRADED_REASON: String = "degraded_reason"

    /**
     * `Boolean` — set when the unit terminated via `CancellationException` (client
     * disconnect, timeout). Deliberately *not* [ERROR]: cancellations must not pollute
     * error rates.
     */
    public const val CANCELLED: String = "cancelled"

    /**
     * `String` — why the unit was cancelled. A pre-set value wins (mirroring [ERROR_REASON]);
     * the adapter/filter default is `"cancelled"`, or `"async_timeout"` for a servlet async
     * timeout.
     */
    public const val CANCEL_REASON: String = "cancel_reason"

    // --- Line composition ---

    /**
     * `String` — the human-readable summary. Written by [JsonCanonicalLineWriter] (folded into
     * the JSON object) when absent; a handler-set value wins — the same check-before-default
     * pattern as [ERROR_REASON]. The MDC/Logstash writers keep the summary as the slf4j event
     * message instead and do not write this field.
     */
    public const val MESSAGE: String = "message"

    // --- Work-unit identity ---

    /**
     * `String` — the work unit's id, written by the adapter and mirrored into slf4j MDC under
     * the same name (see [CanonicalLogMdc]) so ordinary log lines join to the canonical line.
     */
    public const val WORK_UNIT_ID: String = "work_unit_id"

    /** `String` — the work unit kind (`"http"`, ...), written by the adapter. */
    public const val WORK_UNIT_KIND: String = "work_unit_kind"

    /**
     * `String` — the *immediate* parent unit's [WORK_UNIT_ID], written by the core entry
     * points on a unit opened inside another. Omitted on top-level units (absent = no parent).
     */
    public const val PARENT_WORK_UNIT_ID: String = "parent_work_unit_id"

    /**
     * `Long` — nesting depth: 1 for a unit opened inside a top-level unit, 2 inside that, and
     * so on. Written by the core entry points; omitted on top-level lines (absent = depth 0).
     */
    public const val WORK_UNIT_DEPTH: String = "work_unit_depth"

    // --- Background / scheduled jobs (canonical-log-jobrunr, scheduling starter) ---

    /**
     * `String` — the job's logical name, low-cardinality and bounded (one value per job type).
     * Written by the background/scheduled-job adapters (`canonical-log-jobrunr`'s
     * `JobRunrWorkUnitAdapter`, the scheduling starter's `ScheduledJobWorkUnitAdapter`), never core.
     */
    public const val JOB_NAME: String = "job_name"

    /**
     * `String` — the job runner's own identifier for this job (JobRunr's `Job.id`), distinct from
     * [WORK_UNIT_ID] so a canonical line joins back to the runner's dashboard/storage. Written by
     * `canonical-log-jobrunr`'s `JobRunrWorkUnitAdapter`.
     */
    public const val JOB_ID: String = "job_id"

    /**
     * `Long` — the 1-based processing attempt number for this run (first run = 1, first retry = 2,
     * ...), derived from the job's failed-state history. Lets a query separate a first failure from
     * a later retry that succeeded. Written by `canonical-log-jobrunr`'s `JobRunrWorkUnitAdapter`.
     */
    public const val JOB_ATTEMPT: String = "job_attempt"

    // --- Library self-diagnostics (canonical_log_*) ---

    /**
     * `Boolean` — set when an [CanonicalLogContext.increment] hit a key already holding a
     * non-Long. The increment is dropped rather than thrown (telemetry must never fail the
     * observed operation); the conflict is reported here instead. Last conflict wins.
     */
    public const val TYPE_CONFLICT: String = "canonical_log_type_conflict"

    /** `String` — the key that had a put/increment type collision (see [TYPE_CONFLICT]). */
    public const val TYPE_CONFLICT_KEY: String = "canonical_log_type_conflict_key"

    /** `String` — the fully-qualified type already stored at [TYPE_CONFLICT_KEY]. */
    public const val TYPE_CONFLICT_TYPE: String = "canonical_log_type_conflict_type"

    /**
     * `Boolean` — set when `WorkUnitAdapter.enrich` threw. Enrich failures are swallowed and
     * recorded here rather than replacing the block's result (see [WorkUnitAdapter]).
     */
    public const val ENRICH_ERROR: String = "canonical_log_enrich_error"

    /** `String` — fully-qualified class name of the exception a throwing `enrich` raised. */
    public const val ENRICH_ERROR_CLASS: String = "canonical_log_enrich_error_class"

    /**
     * `Boolean` — set when `WorkUnitAdapter.seed` threw. Seed failures are swallowed and
     * recorded here rather than replacing the block's result (see [WorkUnitAdapter.seed]).
     */
    public const val SEED_ERROR: String = "canonical_log_seed_error"

    /** `String` — fully-qualified class name of the exception a throwing `seed` raised. */
    public const val SEED_ERROR_CLASS: String = "canonical_log_seed_error_class"

    /**
     * `Boolean` — set when a *contributor* (an interceptor/listener/event consumer that adds
     * fields from inside the app's critical path) threw while contributing. Like the
     * enrich/seed guards, the throw is swallowed and recorded here: telemetry must never fail
     * the operation it observes, and a contributor sits inside a live DB call, HTTP call, or
     * resilience decoration. Written by the contributor modules, never by core itself.
     */
    public const val CONTRIBUTOR_ERROR: String = "canonical_log_contributor_error"

    /** `String` — fully-qualified class name of the exception a throwing contributor raised. */
    public const val CONTRIBUTOR_ERROR_CLASS: String = "canonical_log_contributor_error_class"

    // --- Trace correlation (written by seeding adapters, not by core itself) ---

    /**
     * `String` — the active distributed-trace id, for the line ↔ trace join. Underscore form
     * of OTel's `trace_id` log-correlation name. **Not written by core**: it's ambient state
     * that only exists at work-unit open on the opening thread, so a seeding adapter captures
     * it in `seed` — `OtelSeedingAdapter` (canonical-log-tracing-otel) from `Span.current()`,
     * or `MdcSeedingAdapter` from a tracing agent's MDC. Absent when no span/id is active
     * (never the all-zeroes sentinel). The library-writes-it rule holds: the seeding module
     * is the library.
     */
    public const val TRACE_ID: String = "trace_id"

    /**
     * `String` — the active span id, companion to [TRACE_ID]. Underscore form of OTel's
     * `span_id` log-correlation name; same capture story (a seeding adapter's `seed`, never
     * core). Absent when no valid span is active.
     */
    public const val SPAN_ID: String = "span_id"

    // --- Inbound HTTP (canonical-log-spring-boot-starter: HttpWorkUnitAdapter) ---

    /** `String` — the request method (`GET`, `POST`, ...). */
    public const val HTTP_REQUEST_METHOD: String = "http_request_method"

    /** `String` — the actual requested path (`/posts/1`); high-cardinality, don't group on it. */
    public const val URL_PATH: String = "url_path"

    /**
     * `String` — the matched route template (`/posts/{id}`) for low-cardinality grouping.
     * Omitted when no template matched (e.g. a 404 before routing), so queries on it don't
     * surface unmatched garbage.
     */
    public const val HTTP_ROUTE: String = "http_route"

    /** `Long` — the response status code as the client sees it (heuristically corrected for late 5xx / 499). */
    public const val HTTP_RESPONSE_STATUS_CODE: String = "http_response_status_code"

    /** `Long` — wall-clock request duration in integer milliseconds. */
    public const val HTTP_REQUEST_DURATION_MS: String = "http_request_duration_ms"

    /**
     * `Boolean` — set by the HTTP adapter when a client-supplied request-id header was present
     * but rejected (too long, or outside the safe `[A-Za-z0-9._-]` charset), so the work unit
     * fell back to a generated UUID. A marker for operators to spot misbehaving clients;
     * omitted (not `false`) when the header is absent/empty or valid — absent is normal, not a
     * rejection.
     */
    public const val X_REQUEST_ID_REJECTED: String = "x_request_id_rejected"

    // --- Outbound HTTP client (canonical-log-okhttp: OkHttpCanonicalInterceptor) ---

    /** `Long` — one increment per user-issued outbound call (transparent retries/redirects don't count). */
    public const val HTTP_CLIENT_REQUEST_COUNT: String = "http_client_request_count"

    /** `Long` — total wall-clock time in `chain.proceed()` across outbound calls, integer ms. */
    public const val HTTP_CLIENT_REQUEST_DURATION_MS_TOTAL: String = "http_client_request_duration_ms_total"

    /** `Long` — outbound calls that got a 4xx response. */
    public const val HTTP_CLIENT_4XX_COUNT: String = "http_client_4xx_count"

    /** `Long` — outbound calls that got a 5xx response. */
    public const val HTTP_CLIENT_5XX_COUNT: String = "http_client_5xx_count"

    /** `Long` — outbound calls that failed with no response (connect refused, DNS, timeout, ...). */
    public const val HTTP_CLIENT_ERROR_COUNT: String = "http_client_error_count"

    // --- Database (canonical-log-jdbc: JdbcCanonicalListener) ---

    /** `Long` — total statement count; a batch of N statements counts as N ("how much SQL?"). */
    public const val DB_QUERY_COUNT: String = "db_query_count"

    /** `Long` — total execution count; a batch counts as 1 ("how many round-trips?"). */
    public const val DB_EXECUTION_COUNT: String = "db_execution_count"

    /**
     * `Long` — total wall-clock time spent on executions (including failed ones), integer ms.
     * Pair with [DB_EXECUTION_COUNT] — not [DB_QUERY_COUNT] — for mean per-round-trip latency.
     */
    public const val DB_EXECUTION_DURATION_MS_TOTAL: String = "db_execution_duration_ms_total"

    /** `Long` — executions whose elapsed time met or exceeded the slow-query threshold (per-execution). */
    public const val DB_SLOW_EXECUTION_COUNT: String = "db_slow_execution_count"

    /** `Long` — failed executions (per-execution, one per failed `afterQuery`). */
    public const val DB_EXECUTION_ERROR_COUNT: String = "db_execution_error_count"

    // --- Outbound Kafka producer (canonical-log-kafka: withCanonicalLogging decorator) ---
    //
    // These are the producer-side *contributor* fields — this module's own aggregates, like
    // db_* / http_client_*, so they graduate to constants. The consumer-side messaging_*
    // fields are NOT here: they stay string literals in KafkaRecordWorkUnitAdapter (the recipe
    // policy — see the field-constants gotcha and docs/recipes/message-consumers.md).

    /** `Long` — one increment per `Producer.send` issued inside a work unit (counted at submit). */
    public const val KAFKA_PRODUCE_COUNT: String = "kafka_produce_count"

    /** `Long` — sends whose acknowledgement completed with an exception (delivery failure). */
    public const val KAFKA_PRODUCE_ERROR_COUNT: String = "kafka_produce_error_count"

    /**
     * `Long` — total wall-clock time from submit to acknowledgement across sends, integer ms.
     * Only acks that land before the line is emitted contribute (snapshot-at-emit cutoff).
     */
    public const val KAFKA_PRODUCE_DURATION_MS_TOTAL: String = "kafka_produce_duration_ms_total"

    // --- Resilience (canonical-log-resilience4j: CanonicalResilience4j) ---
    //
    // Per-work-unit attribution for the resilience layer wrapped around outbound calls:
    // retries and *rejections*. Resilience4j's own Micrometer metrics answer the global
    // question ("how often is this breaker open?"); these answer the per-request one
    // ("did *this* unit retry, or get shed?").
    //
    // The counters are deliberately instance-name-free. A name belongs in a field *value*
    // (see [CIRCUIT_BREAKER_OPEN_NAME]), never in a field name — `retry_attempt_count` stays
    // one queryable field however many Retry instances an app declares. Per-name breakdown is
    // an explicit non-goal here; that's what the Micrometer tags are for.

    /**
     * `Long` — retried attempts within this work unit: one per Resilience4j `on_retry` event,
     * i.e. **excluding the initial call**. Absent (not zero) when nothing retried, so the
     * "did this unit retry at all?" query is a presence check. A unit whose retry eventually
     * succeeded still carries this — that's the point: it separates "3.2s because slow" from
     * "3.2s because we tried three times".
     */
    public const val RETRY_ATTEMPT_COUNT: String = "retry_attempt_count"

    /** `Long` — retries that gave up and rethrew (one per exhausted Retry decoration). */
    public const val RETRY_EXHAUSTED_COUNT: String = "retry_exhausted_count"

    /**
     * `Long` — total backoff wait attributable to this unit's retries, integer ms, summed from
     * the retry events' wait interval. Pair with [RETRY_ATTEMPT_COUNT] to split a slow unit's
     * time into "waiting to retry" versus "actually calling".
     */
    public const val RETRY_WAIT_DURATION_MS_TOTAL: String = "retry_wait_duration_ms_total"

    /**
     * `Long` — calls an open circuit breaker refused (`CallNotPermittedException`). The call was
     * **never attempted**, so it contributes no `http_client_*` fields — a rejected unit shows
     * the rejection and no outbound call, which is exactly how to tell shedding from failure.
     */
    public const val CIRCUIT_BREAKER_REJECTED_COUNT: String = "circuit_breaker_rejected_count"

    /** `Long` — calls the breaker recorded as failures (attempted, and failed). */
    public const val CIRCUIT_BREAKER_FAILURE_COUNT: String = "circuit_breaker_failure_count"

    /**
     * `String` — the name of the circuit-breaker instance that rejected a call in this unit
     * (`"payments-api"`). Last rejection wins. Bounded by construction: breaker names are
     * declared configuration, not request data. Only written on rejection — a breaker that
     * merely recorded a failure doesn't name itself.
     */
    public const val CIRCUIT_BREAKER_OPEN_NAME: String = "circuit_breaker_open_name"

    /** `Long` — calls a full bulkhead (semaphore or thread-pool) refused. */
    public const val BULKHEAD_REJECTED_COUNT: String = "bulkhead_rejected_count"

    /** `Long` — calls a rate limiter did not permit. */
    public const val RATE_LIMITER_REJECTED_COUNT: String = "rate_limiter_rejected_count"

    /** `Long` — calls a time limiter timed out. */
    public const val TIME_LIMITER_TIMEOUT_COUNT: String = "time_limiter_timeout_count"

    /**
     * `Boolean` — set to `true` when *any* resilience rejection or timeout hit this unit
     * (breaker open, bulkhead full, rate limiter exhausted, time limiter fired). Omitted (not
     * `false`) otherwise, like every other boolean here.
     *
     * This is the field to lead a dashboard with: today a shed request and a genuinely failed
     * one both surface as `error=true`, which conflates "the upstream broke" with "we refused
     * to call it". `resilience_rejected="true"` separates them in one predicate.
     */
    public const val RESILIENCE_REJECTED: String = "resilience_rejected"
}
