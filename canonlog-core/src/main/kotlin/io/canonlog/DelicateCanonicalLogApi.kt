package io.canonlog

/**
 * Marks an API that bypasses the standard work-unit lifecycle (open-via-`withCanonicalLog{,Blocking}`,
 * accumulate via contributors and `CanonicalLog.put`, emit via the entry-point's emit lambda).
 *
 * Direct use is legitimate for entry-point implementations (e.g. servlet filters that
 * need to manage the lifecycle across async dispatch) and for testing — but adopter
 * code should generally not need it. Wrapping it in [withCanonicalLogBlocking] or
 * [withCanonicalLog] is the supported path.
 *
 * Opt in at the use site with `@OptIn(DelicateCanonicalLogApi::class)`.
 */
@RequiresOptIn(
    message = "Direct construction bypasses the work-unit lifecycle. Prefer withCanonicalLog or withCanonicalLogBlocking unless you're implementing an entry point or writing a test.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class DelicateCanonicalLogApi
