package io.github.alexhumphreys.canonicallog.servlet

import jakarta.servlet.http.HttpServletRequest

/**
 * A dependency-free path matcher for "should this request produce a canonical line?", so
 * plain-servlet adopters get exclude-paths without pulling in Spring's `AntPathMatcher`.
 *
 * Deliberately two rules, not ant-style globbing:
 *  - an exact match (`/health` matches only `/health`), or
 *  - a prefix match when the pattern ends in a star: a pattern of `/internal/` followed by a
 *    star matches `/internal/anything`, including `/internal/` itself. The prefix is the
 *    pattern with its trailing star removed.
 *
 * Matched against `request.requestURI` — the same value the `url_path` field reports. The
 * Spring starter keeps its own `AntPathMatcher` for back-compat with existing
 * `exclude-paths` values.
 */
public object PathExclusions {
    /**
     * Build an "include this request?" predicate from [patterns]: it returns `true` (include,
     * emit a line) when **no** pattern matches, and `false` (exclude) when any does. An empty
     * list includes everything.
     */
    public fun matcher(patterns: List<String>): (HttpServletRequest) -> Boolean {
        val exact = patterns.filterNot { it.endsWith("*") }.toSet()
        val prefixes = patterns.filter { it.endsWith("*") }.map { it.dropLast(1) }
        return { request ->
            val path = request.requestURI
            val excluded = path in exact || prefixes.any { path.startsWith(it) }
            !excluded
        }
    }
}
