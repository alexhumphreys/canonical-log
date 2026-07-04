package io.github.alexhumphreys.canonicallog

import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * A [CanonicalLineWriter] that emits the canonical line by flattening every snapshot field
 * into slf4j **MDC** for the duration of one log event, then logging the human summary.
 *
 * Use this when your JSON layout includes/flattens MDC but does **not** render logstash
 * `StructuredArguments` — Dropwizard's `EventJsonLayout` (with `flattenMdc: true`) is the
 * canonical example, as are many hand-rolled logback JSON layouts. With such a layout the
 * logstash writer's fields silently vanish (the line arrives as just the human message);
 * routing through MDC makes them appear.
 *
 * Prefer [LogstashCanonicalLineWriter][io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter]
 * (in `canonical-log-logstash`) when the logstash encoder is in play, because it preserves
 * field **types** (counts and durations stay numeric). MDC is `String`-valued, so this writer
 * **stringifies every value** — the documented trade-off: counts/durations arrive as strings
 * downstream. Never wire both writers for the same line.
 *
 * The message defaults to [canonicalLineMessage]; override [message] to change the summary
 * format (or return a constant to restore a plain literal).
 */
public class MdcCanonicalLineWriter(
    loggerName: String = "canonical",
    private val message: (Map<String, Any>) -> String = ::canonicalLineMessage,
) : CanonicalLineWriter {
    private val logger = LoggerFactory.getLogger(loggerName)

    override fun write(context: CanonicalLogContext) {
        val snapshot = context.snapshot()
        // Remember every value we displace so we can restore MDC exactly as we found it —
        // the work_unit_id mirror from CanonicalLogMdc is naturally preserved (same value
        // goes back). Values are stringified because MDC is String-valued.
        val displaced = HashMap<String, String?>(snapshot.size)
        for ((key, value) in snapshot) {
            displaced[key] = MDC.get(key)
            MDC.put(key, value.toString())
        }
        try {
            logger.info(message(snapshot))
        } finally {
            for ((key, previous) in displaced) {
                if (previous == null) MDC.remove(key) else MDC.put(key, previous)
            }
        }
    }
}
