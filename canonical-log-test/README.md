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

## Negative assertions: `hasNoField` / `hasNoFieldMatching`

Assert what is *not* on a line. Both work on a field map or a `CapturedCanonicalLine`, and
both throw `AssertionError` naming the offending key and value:

```kotlin
line.hasNoField(CanonicalFields.ERROR)          // absent means false, so never assert error == false
line.hasNoFieldMatching(Regex("^http_client_")) // this path made no outbound call
```

## Contract harness: `contributorContract` / `adapterContract`

The behavioural pin for "contributors and adapters must not throw". You describe the module
once, and the harness generates the cases. It returns `List<ContractCase>`, which you register
one test per case:

```kotlin
class MyClientContractTest : DescribeSpec({
    describe("contract") {
        contributorContract(
            name = "my client",
            operation = { client.call() },                 // the real operation; repeatable
            expectedFields = mapOf("my_client_call_count" to Long::class),
            allowedFieldPrefixes = setOf("my_client_"),
            failure = failingScenario<IOException>(mapOf("my_client_error_count" to Long::class)) {
                client.callUnreachable()
            },
            adversarial = mapOf("a hostile header" to scenario { client.callWith(header = "\n") }),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
```

`contributorContract` is for code that writes into the unit already bound to the current thread
(an interceptor, a listener, a decorator). Its cases check:

- the declared fields land with their declared types;
- every field follows the naming conventions;
- nothing undeclared is written, and no `canonical_log_*` diagnostic fires;
- no contribution is lost;
- nothing throws with no unit bound, or when the contributor's counter keys already hold a
  `String`;
- the failure path rethrows the operation's *own* exception.

`adapterContract` is for a `WorkUnitAdapter`. It runs each input you give it through the real
lifecycle with the block completing, throwing and being cancelled. Each run must not throw from
`describe` (core doesn't guard `describe`), and the backstop markers must not appear.

Both entry points install the process-wide diagnostic hooks while a case runs, so don't run them
under parallel specs. `runAll()` runs every case and reports all the failures together.
