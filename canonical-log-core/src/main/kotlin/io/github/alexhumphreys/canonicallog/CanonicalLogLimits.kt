package io.github.alexhumphreys.canonicallog

/**
 * Bounds on what one work unit's accumulator will hold, so a misbehaving contribution can't
 * blow up the line downstream (Loki per-line limits, log pipeline cost). Telemetry must never
 * break the app, and that includes never flooding the pipeline.
 *
 * - [maxFields] — distinct keys. Once the map holds this many, a `put`/`increment` that would
 *   mint a **new** key is dropped and counted in `canonical_log_dropped_field_count`, with the
 *   first dropped key in `canonical_log_dropped_field_key`. Overwrites of existing keys always
 *   land. Two kinds of new key are exempt, so a runaway loop in handler code can't cost the line
 *   the fields that make it worth having: keys the library itself defines ([CanonicalFields]
 *   constants — `error`, `http_*`, `db_*`, ...) and anything written during the adapter's
 *   `enrich`. The map can therefore exceed [maxFields] by that bounded set, plus the
 *   `canonical_log_*` markers reporting the breach. It is a soft cap under concurrency: writers
 *   racing past the size check together can each add one key.
 * - [maxValueLength] — characters (not bytes) in a `CharSequence` value. A longer value is cut
 *   to its prefix (never splitting a surrogate pair) and counted in
 *   `canonical_log_truncated_field_count`, with the first truncated key in
 *   `canonical_log_truncated_field_key`. Non-`CharSequence` values aren't measured: the type
 *   rules already keep values to numbers, booleans and short strings.
 *
 * Neither limit ever throws. Set process-wide via [CanonicalLog.limits]; [UNLIMITED] opts out.
 */
public class CanonicalLogLimits @JvmOverloads public constructor(
    public val maxFields: Int = DEFAULT_MAX_FIELDS,
    public val maxValueLength: Int = DEFAULT_MAX_VALUE_LENGTH,
) {
    init {
        require(maxFields > 0) { "maxFields must be positive, was $maxFields" }
        require(maxValueLength > 0) { "maxValueLength must be positive, was $maxValueLength" }
    }

    override fun toString(): String =
        "CanonicalLogLimits(maxFields=$maxFields, maxValueLength=$maxValueLength)"

    public companion object {
        /** Several times a real request line (~30–40 fields), far below anything that hurts. */
        public const val DEFAULT_MAX_FIELDS: Int = 256

        /** 8 KiB of characters: room for a long error message, not for a serialized payload. */
        public const val DEFAULT_MAX_VALUE_LENGTH: Int = 8192

        @JvmField
        public val DEFAULT: CanonicalLogLimits = CanonicalLogLimits()

        @JvmField
        public val UNLIMITED: CanonicalLogLimits = CanonicalLogLimits(Int.MAX_VALUE, Int.MAX_VALUE)
    }
}
