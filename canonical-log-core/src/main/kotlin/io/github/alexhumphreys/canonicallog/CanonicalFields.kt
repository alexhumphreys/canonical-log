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
 * is already present before writing their own default. Everything else the adapter writes
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
}
