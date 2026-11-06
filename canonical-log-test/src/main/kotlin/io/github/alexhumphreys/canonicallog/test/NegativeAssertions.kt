package io.github.alexhumphreys.canonicallog.test

/**
 * Assert that this field map has **no** field [name], failing with the value it found if it does.
 *
 * Negative assertions are how a contributor stays honest about scope: assert that `error` is
 * absent on a success path (never `error == false` — the convention is "absent means false"),
 * that `error_class` is absent after a `markFailed` that didn't throw, that a header-capturing
 * contributor didn't capture the header it wasn't asked for.
 *
 * Throws [AssertionError] (no test-framework types), so it reads the same under Kotest or JUnit:
 * ```kotlin
 * val line = captureCanonicalLineBlocking { service.handle(command) }
 * line.fields.hasNoField(CanonicalFields.ERROR)
 * ```
 */
public fun Map<String, Any>.hasNoField(name: String) {
    if (containsKey(name)) {
        throw AssertionError("expected no field \"$name\", but it was present with value ${describeValue(get(name))}")
    }
}

/**
 * Assert that **no** key in this field map matches [pattern] (anywhere in the key, as
 * [Regex.containsMatchIn] — anchor it with `^`/`$` for a whole-key match), failing with every
 * offending key and its value.
 *
 * The field-creep form of [hasNoField]: "this contributor writes nothing under `http_`", "no
 * `canonical_log_*` diagnostic fired".
 */
public fun Map<String, Any>.hasNoFieldMatching(pattern: Regex) {
    val offending = keys.filter { pattern.containsMatchIn(it) }.sorted()
    if (offending.isNotEmpty()) {
        throw AssertionError(
            "expected no field matching /${pattern.pattern}/, but found: " +
                offending.joinToString { "$it=${describeValue(get(it))}" },
        )
    }
}

/** [hasNoField] against the captured line's [CapturedCanonicalLine.fields]. */
public fun CapturedCanonicalLine<*>.hasNoField(name: String): Unit = fields.hasNoField(name)

/** [hasNoFieldMatching] against the captured line's [CapturedCanonicalLine.fields]. */
public fun CapturedCanonicalLine<*>.hasNoFieldMatching(pattern: Regex): Unit = fields.hasNoFieldMatching(pattern)

internal fun describeValue(value: Any?): String =
    if (value == null) "null" else "$value (${value::class.qualifiedName})"
