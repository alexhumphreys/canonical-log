# canonical-log-okhttp

Framework-agnostic OkHttp `Interceptor` contributing outbound-call aggregates to the active
work unit: `http_client_request_count`, `http_client_request_duration_ms_total`,
`http_client_4xx_count`, `http_client_5xx_count`, `http_client_error_count`
([docs/fields.md](../docs/fields.md)).

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OkHttpCanonicalInterceptor())
    .build()
```

Spring Boot users: use the [starter](../canonical-log-okhttp-spring-boot-starter/README.md),
which ships this as a builder customizer.

## Async `enqueue()` needs the request tag

Synchronous `execute()` just works — the interceptor runs on your bound thread. `enqueue()`
runs it on OkHttp dispatcher threads with no binding, so opt in by tagging the request at
build time (captures the active context on the calling thread):

```kotlin
val request = Request.Builder()
    .url(url)
    .withCanonicalContext()
    .build()
client.newCall(request).enqueue(callback)
```

Untagged `enqueue()` is a silent no-op (no fields, no errors). The work unit does not wait
for queued calls — join them (latch/future) before the unit ends, or the contributions miss
the emit snapshot.
