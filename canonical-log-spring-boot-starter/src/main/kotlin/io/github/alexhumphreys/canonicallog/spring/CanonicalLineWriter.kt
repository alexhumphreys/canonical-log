package io.github.alexhumphreys.canonicallog.spring

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory

/**
 * The sink [CanonicalLogFilter] hands the finalized work unit to — the starter-level
 * counterpart of core's `EmitFn`.
 *
 * **`write` must not throw.** Same contract as core's `EmitFn`: by the time it runs the
 * work unit is already finalized (handler done, adapter enriched, threadlocal restored),
 * so a throwing writer is a wiring bug at the sink level. The filter does not attempt to
 * recover — on the synchronous path the exception replaces the handler's result; on the
 * async path it surfaces from an [jakarta.servlet.AsyncListener] callback where the
 * container's handling is unspecified. Keep implementations dead simple.
 *
 * Register a bean of this type to route canonical lines somewhere other than the default
 * logstash `"canonical"` logger (log4j2, plain JSON, Kafka, ...) — the auto-configuration
 * injects it into the filter.
 */
public fun interface CanonicalLineWriter {
    public fun write(context: CanonicalLogContext)
}

/**
 * Default [CanonicalLineWriter]: logs the snapshot as logstash-logback-encoder structured
 * arguments on the [loggerName] logger (`"canonical"` unless overridden), producing one
 * flat JSON line per work unit when that logger routes to a logstash encoder.
 */
public class LogstashCanonicalLineWriter(loggerName: String = "canonical") : CanonicalLineWriter {
    private val logger = LoggerFactory.getLogger(loggerName)

    override fun write(context: CanonicalLogContext) {
        logger.info("canonical", StructuredArguments.entries(context.snapshot()))
    }
}
