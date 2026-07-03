package io.github.alexhumphreys.canonicallog.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Settings for the HTTP canonical-log filter, bound from `canonical-log.http.*`.
 */
@ConfigurationProperties("canonical-log.http")
public class CanonicalLogHttpProperties {

    /**
     * Whether the HTTP filter is registered at all. Mirrors the
     * `@ConditionalOnProperty` key on [CanonicalLogAutoConfiguration].
     */
    public var enabled: Boolean = true

    /**
     * Ant-style path patterns matched against the request URI (the same value the
     * `url_path` field reports). Matching requests skip the work unit entirely:
     * no context is bound and no canonical line is emitted.
     *
     * Typical use — silence health probes:
     * canonical-log.http.exclude-paths=/actuator/&#42;&#42;
     *
     * (The double asterisk is written as an HTML entity here because a literal
     * slash-asterisk sequence would open a nested Kotlin comment.)
     */
    public var excludePaths: List<String> = emptyList()

    /**
     * Whether the library mirrors the active work unit's id into slf4j MDC under
     * `work_unit_id`, so ordinary log lines written during a request correlate
     * with its canonical line. Applied to the process-wide
     * `CanonicalLogMdc.enabled` switch at startup, so it also governs the core
     * entry points and propagation helpers, not just the HTTP filter. Set to
     * `false` if your shop already manages MDC and doesn't want the library
     * touching it.
     */
    public var mdcEnabled: Boolean = true
}
