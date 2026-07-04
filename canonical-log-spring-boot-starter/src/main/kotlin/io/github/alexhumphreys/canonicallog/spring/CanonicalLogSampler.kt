package io.github.alexhumphreys.canonicallog.spring

/**
 * @deprecated Moved to core. Use [io.github.alexhumphreys.canonicallog.CanonicalLogSampler].
 * This alias keeps existing sampler beans compiling and will be removed after one release.
 */
@Deprecated(
    "Moved to canonical-log-core",
    ReplaceWith(
        "CanonicalLogSampler",
        "io.github.alexhumphreys.canonicallog.CanonicalLogSampler",
    ),
)
public typealias CanonicalLogSampler = io.github.alexhumphreys.canonicallog.CanonicalLogSampler
