package io.canonlog.sample

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Marker

/**
 * Reads the canonical-line fields back from a captured log event for assertions.
 *
 * Reflection is used because logstash-logback-encoder's [MapEntriesAppendingMarker]
 * has no public accessor for its underlying map (only `writeTo(JsonGenerator)`,
 * which would lose Kotlin-side type fidelity — `Long` vs `Int`). If the encoder
 * ever exposes a public `getFieldMap()`, swap to that.
 */
private fun snapshotOf(event: ILoggingEvent): Map<String, Any> {
    val args: Array<out Any?> = event.argumentArray ?: emptyArray()
    val markers: List<Any> = (event.markerList ?: emptyList<Marker>()) + args.filterNotNull()
    return markers.filterIsInstance<MapEntriesAppendingMarker>()
        .map { marker ->
            val mapField = generateSequence<Class<*>>(marker::class.java) { it.superclass }
                .firstNotNullOfOrNull { cls ->
                    cls.declaredFields.firstOrNull { f -> Map::class.java.isAssignableFrom(f.type) }
                } ?: error("no map field on ${marker::class.java}")
            mapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            mapField.get(marker) as Map<String, Any>
        }
        .fold(emptyMap()) { acc, m -> acc + m }
}

/**
 * The most recent canonical line in the appender, or `null` if none was captured.
 * Tests that *require* a line should `!!` so a missing emit fails loudly rather
 * than as "field not present."
 */
internal fun lastCanonicalSnapshot(appender: ListAppender<ILoggingEvent>): Map<String, Any>? =
    appender.list.lastOrNull { it.loggerName == "canonical" }?.let(::snapshotOf)

/**
 * Every canonical line captured by the appender, in emission order. Used by load
 * tests that assert on per-line invariants (one work unit per request, no field
 * bleeding between concurrent requests, etc).
 */
internal fun allCanonicalSnapshots(appender: ListAppender<ILoggingEvent>): List<Map<String, Any>> =
    appender.list.filter { it.loggerName == "canonical" }.map(::snapshotOf)
