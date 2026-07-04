package io.github.alexhumphreys.canonicallog

/**
 * Compose a short, human-skimmable message line from a canonical field snapshot — the text a
 * person sees in a plain console (`kubectl logs`, local dev) where the structured JSON is noise.
 * Stripe-style: `GET /posts/{id} 200 12ms`. All fields still live structured on the line; this is
 * purely the log event's message, and query authors must never parse it.
 *
 * Shape:
 * - **HTTP work units** (have [CanonicalFields.HTTP_REQUEST_METHOD]):
 *   `<method> <route|url_path> <status> <duration>ms`, each part appended only if present.
 * - **Other work units** (scheduled jobs, etc.): `<work_unit_kind> <work_unit_id>` — the
 *   entry-point-specific fields (a job's name, a message's topic) aren't core-known, so the
 *   generic identity is used; an entry point that wants richer text supplies its own formatter.
 * - When `error=true`, an ` error=<reason>` hint is appended (or ` error` with no reason).
 *
 * Pure and side-effect-free; lives in core so every emit site (the HTTP `LogstashCanonicalLineWriter`
 * and the scheduling starter's sink) produces the same text without depending on each other.
 */
public fun canonicalLineMessage(fields: Map<String, Any>): String {
    val base = when (val method = fields[CanonicalFields.HTTP_REQUEST_METHOD] as? String) {
        null -> {
            val kind = fields[CanonicalFields.WORK_UNIT_KIND] as? String ?: "work_unit"
            when (val id = fields[CanonicalFields.WORK_UNIT_ID] as? String) {
                null -> kind
                else -> "$kind $id"
            }
        }
        else -> buildString {
            append(method)
            (fields[CanonicalFields.HTTP_ROUTE] ?: fields[CanonicalFields.URL_PATH])?.let { append(' ').append(it) }
            fields[CanonicalFields.HTTP_RESPONSE_STATUS_CODE]?.let { append(' ').append(it) }
            fields[CanonicalFields.HTTP_REQUEST_DURATION_MS]?.let { append(' ').append(it).append("ms") }
        }
    }
    if (fields[CanonicalFields.ERROR] != true) return base
    return when (val reason = fields[CanonicalFields.ERROR_REASON] as? String) {
        null -> "$base error"
        else -> "$base error=$reason"
    }
}
