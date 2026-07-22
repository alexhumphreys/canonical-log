# canonical-log-okhttp-spring-boot-starter

Auto-configures the [OkHttp contributor](../canonical-log-okhttp/README.md) as an
`OkHttpClientBuilderCustomizer` bean. **Unlike the HTTP filter and JDBC starters, this one
requires adopter participation**: an `OkHttpClient` is configured at builder time, so no
starter can attach an interceptor transparently without either mutating a built client
behind your back or shipping a conflicting default client. Apply the customizers where you
build your client:

```kotlin
@Bean
fun okHttpClient(customizers: List<OkHttpClientBuilderCustomizer>): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // ... your config ...
    customizers.forEach { it.customize(builder) }
    return builder.build()
}
```

The list composes: the canonical customizer ships as
`canonicalOkHttpClientBuilderCustomizer`, and your own `OkHttpClientBuilderCustomizer` beans
(auth, logging, retries) stack alongside it. The same shape works for
`RestTemplateBuilder` / `WebClient.Builder` users — it mirrors Spring's own customizer
pattern.

Fields contributed: `http_client_*` — see [docs/fields.md](../docs/fields.md).
Async `enqueue()` calls need the request tag — see the
[contributor README](../canonical-log-okhttp/README.md).

Opt out with `canonical-log.okhttp.enabled=false`.
