package io.github.alexhumphreys.canonicallog.okhttp

import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import okhttp3.Request

/**
 * Attach a canonical [CanonicalLogContext] to this request so [OkHttpCanonicalInterceptor]
 * can find the work unit even when the call runs on a thread that has no binding — i.e. the
 * `Call.enqueue()` path, where the interceptor executes on OkHttp's dispatcher threads.
 *
 * Call this at request-build time, on the thread where the work unit is active (the caller's
 * thread), so the default argument captures the right context:
 *
 * ```
 * val request = Request.Builder()
 *     .url(url)
 *     .withCanonicalContext() // captures currentCanonicalContext() here, on the calling thread
 *     .build()
 * client.newCall(request).enqueue(callback) // interceptor reads the tag on the dispatcher thread
 * ```
 *
 * Synchronous `execute()` calls don't need this — the interceptor resolves the calling
 * thread's binding directly. Tagging them anyway is harmless (the tag simply wins over the
 * identical thread binding).
 *
 * When [context] is `null` (no work unit active at capture time) no tag is attached and the
 * call stays a no-op, matching the ambient API's behaviour outside a work unit. The tag is
 * keyed by `CanonicalLogContext::class.java`, so a second call overwrites the first.
 */
public fun Request.Builder.withCanonicalContext(
    context: CanonicalLogContext? = currentCanonicalContext(),
): Request.Builder = apply {
    if (context != null) {
        tag(CanonicalLogContext::class.java, context)
    }
}
