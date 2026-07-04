package io.github.alexhumphreys.canonicallog.spring

/**
 * @deprecated Moved to `canonical-log-servlet`. Use
 * [io.github.alexhumphreys.canonicallog.servlet.HttpExchange]. These types appear in adopter
 * code (`WorkUnitAdapter<HttpExchange>` beans), so this alias keeps that compiling and will be
 * removed after one release.
 */
@Deprecated(
    "Moved to canonical-log-servlet",
    ReplaceWith(
        "HttpExchange",
        "io.github.alexhumphreys.canonicallog.servlet.HttpExchange",
    ),
)
public typealias HttpExchange = io.github.alexhumphreys.canonicallog.servlet.HttpExchange

/**
 * @deprecated Moved to `canonical-log-servlet`. Use
 * [io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter]. This alias keeps existing
 * `HttpWorkUnitAdapter()` references compiling and will be removed after one release.
 *
 * Note: a bare `HttpWorkUnitAdapter()` now resolves the route template from the
 * [io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter.ROUTE_ATTRIBUTE] request
 * attribute, **not** Spring's `BEST_MATCHING_PATTERN_ATTRIBUTE`. The starter's default filter
 * wires a Spring-aware route resolver ([springRouteResolver]); a custom adapter that needs
 * Spring route templates should pass it too.
 */
@Deprecated(
    "Moved to canonical-log-servlet",
    ReplaceWith(
        "HttpWorkUnitAdapter",
        "io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter",
    ),
)
public typealias HttpWorkUnitAdapter = io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter
