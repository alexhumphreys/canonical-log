# canonical-log-test

Test kit for adopters (test-scope dependency). Framework-agnostic beyond `logback-classic`;
no kotest/JUnit types in main.

## `captureCanonicalLine { }` / `captureCanonicalLineBlocking { }`

Run a block through the real work-unit lifecycle and get back a `CapturedCanonicalLine`:
the emit-time field snapshot, the `WorkUnit`, the `Outcome`, and the block's
result/exception. Block exceptions are captured, not rethrown, so failure lines are
directly assertable:

```kotlin
val line = captureCanonicalLineBlocking {
    service.handle(command) // calls CanonicalLog.put / markFailed internally
}
line.fields["error_reason"] shouldBe "post_not_found"
line.fields.containsKey("error_class") shouldBe false
```

Use this whenever the code under test can be driven in-process.

## Context fixtures

`testCanonicalLogContext(id, kind)` spins up a bare accumulator without a lifecycle;
`withBoundCanonicalContext(ctx) { }` binds it as the ambient context (try/finally restore)
so contributors and `CanonicalLog.put` land in it. For unit-testing a contributor in
isolation.

## `RecordingCanonicalAppender`

For lines that must come out of a real booted stack on a producer thread (Jetty/Dropwizard,
a `@Scheduled` thread, a broker consumer) — the case that makes hand-rolled
`ListAppender.list` reads flaky. `attach()` hooks the `"canonical"` logger;
`awaitLine(where)` polls a defensive snapshot and requires exactly one match, returning the
flattened field map (both logstash-marker and MDC line shapes supported);
`assertNoLine(where)` is the negative form. `AutoCloseable`.

Note when booting Spring in tests: attach appenders *after* `SpringApplication.run` —
Spring's logback init clears appenders attached before boot.
