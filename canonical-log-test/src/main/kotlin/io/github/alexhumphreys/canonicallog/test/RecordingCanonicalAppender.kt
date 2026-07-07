package io.github.alexhumphreys.canonicallog.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.slf4j.Marker

/**
 * Observes canonical log lines emitted by a *real booted stack* (a Jetty request thread, a
 * Spring scheduler thread, a broker consumer) through a logback [ListAppender] attached to the
 * `"canonical"` logger.
 *
 * This is the counterpart to [captureCanonicalLine]/[captureCanonicalLineBlocking]: use the
 * capture harness whenever the code under test can be driven **in-process** (it runs the block
 * through the lifecycle directly and hands back the snapshot). Reach for this appender only for
 * the *observe-a-real-booted-stack* case the capture harness can't cover — the line has to come
 * out of the actual container/context, on a producer thread with no happens-before edge to the
 * test thread's read.
 *
 * That producer-thread emit is exactly what made hand-rolled `appender.list` reads flaky:
 *
 * 1. **Snapshot before iterating.** [events] never exposes the live list; every read copies into
 *    a fresh list (`ArrayList(list)`, whose copy uses `toArray()` and so never throws
 *    `ConcurrentModificationException`) — safe even while a producer thread appends.
 * 2. **Await, don't read-once.** [awaitLine] polls to a timeout; the emit can land after the
 *    request/response returned.
 * 3. **Match by a discriminator + assert exactly-one.** [awaitLine] keys on a caller predicate
 *    (typically a field that identifies *this* unit — `url_path`, `work_unit_kind`, `job_name`)
 *    and requires exactly one match, so a straggler from another test can't be miscounted and a
 *    genuine field-bleed regression still fails.
 *
 * The poll is a plain [Thread.sleep] loop — no coroutines — so the helper stays usable from any
 * test style; kotest callers can still `runTest`-wrap around it.
 *
 * [AutoCloseable] so specs can `beforeSpec { attach() }` / `afterSpec { close() }` (or use it as
 * a `use { }` resource) and detach cleanly.
 */
public class RecordingCanonicalAppender private constructor(
    private val logger: LogbackLogger,
    private val appender: ListAppender<ILoggingEvent>,
) : AutoCloseable {

    public companion object {
        /** The logger the library emits canonical lines on; [awaitLine] filters on this name. */
        public const val CANONICAL_LOGGER_NAME: String = "canonical"

        private const val POLL_INTERVAL_MS: Long = 25L

        /**
         * Attach a recording [ListAppender] and set the target logger to `INFO`.
         *
         * [loggerName] is the *attach point*. It defaults to the `"canonical"` logger, which is
         * all most specs need. Pass a higher logger (e.g. `org.slf4j.Logger.ROOT_LOGGER_NAME`)
         * to also observe sibling loggers through the same appender — for example asserting an
         * ordinary handler log line carries the `work_unit_id` MDC. Regardless of where it is
         * attached, [awaitLine]/[assertNoLine] only ever match the canonical line by its own
         * fixed logger name ([CANONICAL_LOGGER_NAME]).
         */
        public fun attach(loggerName: String = CANONICAL_LOGGER_NAME): RecordingCanonicalAppender {
            val logger = LoggerFactory.getLogger(loggerName) as LogbackLogger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            logger.level = Level.INFO
            return RecordingCanonicalAppender(logger, appender)
        }
    }

    /** A defensive snapshot of every event seen so far. Never the live list. */
    public fun events(): List<ILoggingEvent> = snapshot()

    /**
     * Poll until **exactly one** canonical line matches [where], then return its flattened field
     * map. Throws if none appears within [timeoutMs], or immediately if more than one matches
     * (once emitted a line never disappears, so >1 is a permanent field-bleed signal, not a
     * transient the poll should ride out).
     *
     * [where] receives the raw [ILoggingEvent]; key on [canonicalFields] for marker-shaped lines
     * (`{ canonicalFields(it)["work_unit_kind"] == "scheduled_job" }`) or on `mdcPropertyMap`
     * directly for MDC-shaped lines.
     */
    public fun awaitLine(
        timeoutMs: Long = 5_000,
        where: (ILoggingEvent) -> Boolean,
    ): Map<String, Any> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val matches = canonicalEvents().filter(where)
            when {
                matches.size == 1 -> return canonicalFields(matches.single())
                matches.size > 1 -> error(
                    "expected exactly one canonical line matching the predicate, found ${matches.size} " +
                        "(a field-bleed regression, or the predicate is not specific enough)",
                )
                System.currentTimeMillis() >= deadline -> error(
                    "no canonical line matched the predicate within ${timeoutMs}ms " +
                        "(saw ${canonicalEvents().size} canonical line(s) total)",
                )
                else -> Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Negative form of [awaitLine]: assert that **no** canonical line matches [where] for a
     * [withinMs] settle window. Throws the moment a match appears. Use for "this path opened no
     * work unit" checks (an excluded route, a filtered request).
     */
    public fun assertNoLine(
        withinMs: Long = 500,
        where: (ILoggingEvent) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (canonicalEvents().any(where)) {
                error("expected no canonical line matching the predicate within ${withinMs}ms, but one appeared")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /** Drop all recorded events (e.g. between requests in a shared-spec appender). */
    public fun clear() {
        appender.list.clear()
    }

    /** Detach the appender and stop it. */
    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun canonicalEvents(): List<ILoggingEvent> =
        snapshot().filter { it.loggerName == CANONICAL_LOGGER_NAME }

    // A plain ListAppender.list is an ArrayList mutated without synchronization on the producer
    // thread. Copy via the ArrayList(Collection) constructor (toArray()-based, never CME-throws)
    // and drop any trailing null a racing append may have left visible before its element write
    // became visible.
    private fun snapshot(): List<ILoggingEvent> = ArrayList(appender.list).filterNotNull()
}

/**
 * Flatten a canonical [event] into its field map, supporting both line shapes the library emits:
 *
 * - **logstash markers** — `LogstashCanonicalLineWriter` attaches the snapshot as a
 *   `MapEntriesAppendingMarker`; its backing map is read reflectively (detected by class name, so
 *   this stays free of a compile dependency on logstash-logback-encoder). Field *types* are
 *   preserved (`Long` counts/durations stay `Long`).
 * - **MDC** — `MdcCanonicalLineWriter` flattens the snapshot into `mdcPropertyMap` (every value
 *   stringified), as Dropwizard's `EventJsonLayout` stack does.
 *
 * MDC is the base; marker fields overlay it (typed values win over their stringified MDC mirror),
 * so servlet/logstash-writer and MDC-writer stacks both read correctly.
 */
public fun canonicalFields(event: ILoggingEvent): Map<String, Any> {
    val fromMdc: Map<String, Any> = event.mdcPropertyMap ?: emptyMap()
    val candidates: List<Any> =
        (event.markerList ?: emptyList<Marker>()) + (event.argumentArray?.filterNotNull() ?: emptyList())
    val fromMarkers = candidates
        .mapNotNull(::markerMap)
        .fold(emptyMap<String, Any>()) { acc, m -> acc + m }
    return fromMdc + fromMarkers
}

// logstash's MapEntriesAppendingMarker carries the field map in a private Map field. Detect it by
// class name (walking the hierarchy) so no logstash type is referenced at compile time, then pull
// the first Map field reflectively — the same walk the scheduling test used to hand-roll.
private fun markerMap(candidate: Any): Map<String, Any>? {
    val hierarchy = generateSequence<Class<*>>(candidate.javaClass) { it.superclass }
    if (hierarchy.none { it.simpleName == "MapEntriesAppendingMarker" }) return null
    val mapField = generateSequence<Class<*>>(candidate.javaClass) { it.superclass }
        .firstNotNullOfOrNull { cls ->
            cls.declaredFields.firstOrNull { f -> Map::class.java.isAssignableFrom(f.type) }
        } ?: return null
    mapField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return mapField.get(candidate) as? Map<String, Any>
}
