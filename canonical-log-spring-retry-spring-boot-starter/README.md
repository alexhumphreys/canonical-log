# canonical-log-spring-retry-spring-boot-starter

Retry attempts and exhaustion from **Spring's retry support** on the canonical line. Add the
dependency; there is no wiring step:

```kotlin
implementation("io.github.alexhumphreys:canonical-log-spring-retry-spring-boot-starter")
```

Covers both retry stacks, independently — an app can use either or both:

| Stack | Seam | Needs |
| --- | --- | --- |
| Spring Framework's built-in `@Retryable` (`org.springframework.resilience`, Framework 7+) | `MethodRetryEvent` application event | nothing beyond Spring Boot 4 |
| Classic Spring Retry (`org.springframework.retry`, `@Retryable` / `RetryTemplate`) | a `RetryListener` bean, which `@EnableRetry` applies to every retryable method | `spring-retry` on the classpath |

Neither path wraps or re-proxies anything: registering a listener bean *is* the integration.

## Fields

- `retry_attempt_count` — retried attempts, excluding the initial call.
- `retry_exhausted_count` — the operation gave up and rethrew.

These are the same constants [`canonical-log-resilience4j`](../canonical-log-resilience4j/README.md)
writes, on purpose: they name the concept, not the library, so one query answers "did this
request retry?" across an app that uses either — or migrates between them.

`retry_wait_duration_ms_total` is **not** written here. Neither Spring stack exposes the backoff
interval to its listener, and a number inferred from wall-clock between callbacks wouldn't be the
backoff. An honest gap beats a plausible-looking wrong field; the Resilience4j contributor fills
that field because its events actually carry the interval.

Opt out with `canonical-log.spring-retry.enabled=false`. Supply your own
`CanonicalSpringRetryListener` / `CanonicalMethodRetryListener` bean and the auto-configuration
backs off for that half.

Notes:

- Both stacks are synchronous, so callbacks run on the thread the work unit is bound to. The one
  wiring choice that disables the built-in half is replacing the context's
  `applicationEventMulticaster` with an asynchronous one — the events then fire on a task
  executor where no work unit is bound, and the contributions silently no-op.
- Telemetry never breaks the call it observes: with no work unit bound this is a no-op, and an
  unexpected throw is recorded as `canonical_log_contributor_error` rather than propagating.
- Classic Spring Retry is `compileOnly` — only its `RetryListener` SPI and `RetryContext` are
  touched, so your own version wins at runtime. Built against 2.0.x.
- `@ConcurrencyLimit` rejections (Framework 7's bulkhead equivalent) are **not** covered: it
  throws `InvocationRejectedException` with no listener or event seam, so there is no
  transparent hook to observe. Resilience4j's bulkhead is covered by the other module.
