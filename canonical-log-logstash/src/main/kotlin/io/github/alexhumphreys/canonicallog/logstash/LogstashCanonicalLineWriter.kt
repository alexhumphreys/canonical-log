package io.github.alexhumphreys.canonicallog.logstash

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.canonicalLineMessage
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory

/**
 * Default [CanonicalLineWriter]: logs the snapshot as logstash-logback-encoder structured
 * arguments on the [loggerName] logger (`"canonical"` unless overridden), producing one
 * flat JSON line per work unit when that logger routes to a logstash encoder.
 *
 * The log event's **message** is a human-skimmable summary ([canonicalLineMessage] by default,
 * e.g. `GET /posts/{id} 200 12ms`) so the line is readable in a plain console; all fields are
 * still attached structured via [StructuredArguments], preserving their **types** (counts and
 * durations stay numeric). Override [message] to change the summary format (or return a constant
 * to restore the old literal `"canonical"` message). The message never contains SLF4J `{}`
 * placeholders, so the structured argument is not consumed by it.
 *
 * Prefer [MdcCanonicalLineWriter][io.github.alexhumphreys.canonicallog.MdcCanonicalLineWriter]
 * only when your layout flattens MDC but does not understand `StructuredArguments`; that writer
 * stringifies every value. Never wire both for the same line.
 */
public class LogstashCanonicalLineWriter(
    loggerName: String = "canonical",
    private val message: (Map<String, Any>) -> String = ::canonicalLineMessage,
) : CanonicalLineWriter {
    private val logger = LoggerFactory.getLogger(loggerName)

    override fun write(context: CanonicalLogContext) {
        val snapshot = context.snapshot()
        logger.info(message(snapshot), StructuredArguments.entries(snapshot))
    }
}
