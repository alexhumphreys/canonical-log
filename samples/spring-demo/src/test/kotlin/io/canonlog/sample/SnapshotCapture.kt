package io.canonlog.sample

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Marker

/**
 * Reads the canonical-line fields back from the most recent log event for assertions.
 *
 * Reflection is used because logstash-logback-encoder's [MapEntriesAppendingMarker]
 * has no public accessor for its underlying map (only `writeTo(JsonGenerator)`,
 * which would lose Kotlin-side type fidelity — `Long` vs `Int`). If the encoder
 * ever exposes a public `getFieldMap()`, swap to that.
 *
 * Returns `null` when no canonical line was captured. Tests that *require* a line
 * should `!!` the result so a missing emit fails loudly rather than as "field not
 * present."
 */
internal fun lastCanonicalSnapshot(appender: ListAppender<ILoggingEvent>): Map<String, Any>? {
    val event = appender.list.lastOrNull { it.loggerName == "canonical" } ?: return null
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
        .fold(emptyMap<String, Any>()) { acc, m -> acc + m }
}
