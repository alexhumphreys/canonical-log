package io.github.alexhumphreys.canonicallog.spring

/**
 * @deprecated Moved to core. Use [io.github.alexhumphreys.canonicallog.CanonicalLineWriter].
 * This alias keeps existing writer beans compiling and will be removed after one release.
 */
@Deprecated(
    "Moved to canonical-log-core",
    ReplaceWith(
        "CanonicalLineWriter",
        "io.github.alexhumphreys.canonicallog.CanonicalLineWriter",
    ),
)
public typealias CanonicalLineWriter = io.github.alexhumphreys.canonicallog.CanonicalLineWriter

/**
 * @deprecated Moved to `canonical-log-logstash`. Use
 * [io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter]. This alias
 * keeps existing references compiling and will be removed after one release.
 */
@Deprecated(
    "Moved to canonical-log-logstash",
    ReplaceWith(
        "LogstashCanonicalLineWriter",
        "io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter",
    ),
)
public typealias LogstashCanonicalLineWriter =
    io.github.alexhumphreys.canonicallog.logstash.LogstashCanonicalLineWriter
