package io.canonlog.okhttp.spring

import okhttp3.OkHttpClient

/**
 * Pre-`build()` customization hook for an [OkHttpClient.Builder].
 *
 * The OkHttp starter exposes its canonical-line wiring as one of these instead of
 * a `BeanPostProcessor` that wraps `OkHttpClient` beans, because `OkHttpClient` is
 * configured at the builder stage — there's no safe way to mutate a built client
 * without producing a different instance, which would silently break anyone who
 * holds a reference to the original.
 *
 * Adopters consume the list of customizers where they construct their client:
 *
 * ```kotlin
 * @Bean
 * fun okHttpClient(customizers: List<OkHttpClientBuilderCustomizer>): OkHttpClient {
 *     val builder = OkHttpClient.Builder()
 *         .connectTimeout(5, TimeUnit.SECONDS)
 *     customizers.forEach { it.customize(builder) }
 *     return builder.build()
 * }
 * ```
 *
 * Multiple customizers compose by simply being applied in order — the canonical
 * one ships under the bean name `canonicalOkHttpClientBuilderCustomizer`; user
 * customizers can be registered as additional beans of the same type without
 * displacing it.
 */
public fun interface OkHttpClientBuilderCustomizer {
    public fun customize(builder: OkHttpClient.Builder)
}
