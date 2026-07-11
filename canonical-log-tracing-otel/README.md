# canonical-log-tracing-otel

Trace correlation: wrap your adapter in `OtelSeedingAdapter` and every canonical line opened
inside a span carries `trace_id` / `span_id`, so a bad line jumps straight to its
distributed trace.

```kotlin
// e.g. as a Spring bean replacing the default HTTP adapter
@Bean
fun httpAdapter(): WorkUnitAdapter<HttpExchange> =
    OtelSeedingAdapter(HttpWorkUnitAdapter(springRouteResolver))
```

It reads `Span.current()` from the OpenTelemetry API at work-unit **open** (the `seed` hook,
on the opening thread — by `enrich` time the span may be ended and the thread an async
listener's) and writes the ids only when the span context is valid, never the all-zeroes
sentinel. It creates no spans and reads no baggage. The OTel API is `compileOnly` — your own
OTel version wins at runtime; core never gains an OTel dependency.

Zero-dependency alternative: if your tracing agent already mirrors ids into slf4j MDC, use
core's `MdcSeedingAdapter` instead:

```kotlin
MdcSeedingAdapter(delegate, mapOf(
    "trace_id" to CanonicalFields.TRACE_ID,
    "dd.trace_id" to CanonicalFields.TRACE_ID,
))
```

Absent MDC keys cost nothing. Micrometer users get OTel ids via their OTel bridge — no
Micrometer-specific module is needed.
