package io.github.alexhumphreys.canonicallog

import org.slf4j.LoggerFactory

/**
 * Serialize a canonical field snapshot as one **flat JSON object**, keys sorted
 * lexicographically (deterministic output for tests and diffs). Hand-rolled — no JSON
 * library — kept safe by the accumulator's own type discipline (flat map of primitives,
 * strings, enums; no nesting).
 *
 * Value mapping:
 * - `String`/`CharSequence` → JSON string with full RFC 8259 escaping (`"`, `\`, and every
 *   control char below `0x20`: `\n` `\r` `\t` `\b` `\f` named, the rest `\u00XX`).
 * - `Long`/`Int`/`Short`/`Byte` → JSON number, unquoted.
 * - `Boolean` → `true`/`false`.
 * - `Double`/`Float` → finite values as JSON numbers; `NaN`/`Infinity`/`-Infinity` → their
 *   **string** form (JSON has no representation for them, and the type rules discourage floats
 *   anyway — this never throws).
 * - `Enum` → its `name`, as a string.
 * - Everything else → `toString()`, escaped as a string. Lists and maps get no special handling:
 *   the type rules say they must never reach the accumulator, so their `toString()` landing as an
 *   ugly string is the honest, non-throwing outcome (and a visible smell).
 *
 * Keys are escaped identically to string values. Nulls can't occur ([CanonicalLogContext.put]
 * drops them) but are skipped defensively. **Never throws** (the writer contract): a `toString()`
 * that throws is caught per entry and rendered as `"<serialization_failed: FQCN>"` — telemetry
 * never fails the emit for one bad value.
 */
public fun canonicalLineJson(fields: Map<String, Any>): String {
    val sb = StringBuilder(fields.size * 24 + 2)
    sb.append('{')
    var first = true
    for (key in fields.keys.sorted()) {
        val value = fields[key] ?: continue
        if (!first) sb.append(',')
        first = false
        appendJsonString(sb, key)
        sb.append(':')
        appendJsonValue(sb, value)
    }
    sb.append('}')
    return sb.toString()
}

private fun appendJsonValue(sb: StringBuilder, value: Any) {
    when (value) {
        is CharSequence -> appendJsonString(sb, value)
        is Long, is Int, is Short, is Byte -> sb.append(value.toString())
        is Boolean -> sb.append(if (value) "true" else "false")
        is Double -> if (value.isFinite()) sb.append(value.toString()) else appendJsonString(sb, value.toString())
        is Float -> if (value.isFinite()) sb.append(value.toString()) else appendJsonString(sb, value.toString())
        is Enum<*> -> appendJsonString(sb, value.name)
        else -> appendJsonString(sb, safeToString(value))
    }
}

private fun safeToString(value: Any): String =
    try {
        value.toString()
    } catch (e: Exception) {
        "<serialization_failed: ${value.javaClass.name}>"
    }

private val HEX = "0123456789abcdef".toCharArray()

private fun appendJsonString(sb: StringBuilder, value: CharSequence) {
    sb.append('"')
    for (i in 0 until value.length) {
        val c = value[i]
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else ->
                if (c < ' ') {
                    sb.append('\\').append('u')
                    sb.append(HEX[(c.code shr 12) and 0xF])
                    sb.append(HEX[(c.code shr 8) and 0xF])
                    sb.append(HEX[(c.code shr 4) and 0xF])
                    sb.append(HEX[c.code and 0xF])
                } else {
                    sb.append(c)
                }
        }
    }
    sb.append('"')
}

/**
 * A [CanonicalLineWriter] whose emitted **log message is the complete, typed JSON object**
 * (via [canonicalLineJson]) — a dependency-free typed sink for when you route the `"canonical"`
 * logger straight to a `%msg%n` (or otherwise raw) appender and want the message itself to be
 * the parseable line.
 *
 * Sink chooser:
 * - logstash-logback-encoder configured →
 *   [LogstashCanonicalLineWriter][io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter]
 *   (`canonical-log-logstash`) — typed fields via `StructuredArguments`.
 * - JSON layout that flattens **MDC** but not `StructuredArguments` (Dropwizard's
 *   `EventJsonLayout`) → [MdcCanonicalLineWriter] — works anywhere, but stringifies every value.
 * - dedicated raw appender / anything else → **this writer** — typed JSON with zero encoder
 *   dependency.
 *
 * The [Logstash][io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter] and
 * [MDC][MdcCanonicalLineWriter] writers keep the human summary as the log *message* and attach
 * fields alongside. Here the JSON **is** the message, so the human summary from
 * [canonicalLineMessage] is folded **inside** the object under a `"message"` key to keep the line
 * skimmable — unless the snapshot already carries a `message` field (handler-owned; not clobbered,
 * mirroring the check-before-default pattern used for `error_reason`).
 *
 * [emitLine] decouples serialization from transport: the default logs the JSON string as the event
 * message via the single-arg `logger.info(String)` overload, which does **no** `{}` placeholder
 * substitution (safe — the JSON contains braces). Adopters can pass a file/stdout/socket lambda
 * instead. [canonicalLineJson] is public so any custom sink can reuse the exact serialization.
 *
 * (Forward note: the `canonical-log-dropwizard` bundle, todo 022, will offer this as its default
 * sink when the deployment's JSON layout is a raw message appender rather than `EventJsonLayout`.)
 */
public class JsonCanonicalLineWriter(
    loggerName: String = "canonical",
    private val emitLine: (String) -> Unit = defaultSlf4jEmit(loggerName),
) : CanonicalLineWriter {
    override fun write(context: CanonicalLogContext): Unit = emitLine(canonicalLineJson(withMessage(context.snapshot())))
}

/** Fold the human summary in under `"message"` unless the handler already owns that key. */
private fun withMessage(snapshot: Map<String, Any>): Map<String, Any> {
    if (snapshot.containsKey("message")) return snapshot
    val enriched = HashMap<String, Any>(snapshot)
    enriched["message"] = canonicalLineMessage(snapshot)
    return enriched
}

/** Default transport: log the JSON as the event message on [loggerName] (no `{}` substitution). */
private fun defaultSlf4jEmit(loggerName: String): (String) -> Unit {
    val logger = LoggerFactory.getLogger(loggerName)
    return { json -> logger.info(json) }
}
