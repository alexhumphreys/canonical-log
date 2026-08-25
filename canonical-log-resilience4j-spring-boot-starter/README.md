# canonical-log-resilience4j-spring-boot-starter

Auto-configures [`canonical-log-resilience4j`](../canonical-log-resilience4j/README.md)
against every Resilience4j registry bean in the context — the ones
`resilience4j-spring-boot3` publishes, or your own. Add the dependency; there is no wiring
step:

```kotlin
implementation("io.github.alexhumphreys:canonical-log-resilience4j-spring-boot-starter")
```

Retries and rejections then appear on the canonical line of whatever work unit made the call
(`retry_attempt_count`, `resilience_rejected`, … — see [docs/fields.md](../docs/fields.md)),
including for `Retry`/`CircuitBreaker` instances the registries create lazily on first use.

Opt out with `canonical-log.resilience4j.enabled=false`.

The registration runs from a `CanonicalResilience4jRegistrar` bean
(`SmartInitializingSingleton` + `ObjectProvider`) rather than `@ConditionalOnBean` beans:
registration is an action that must happen once, after the registries exist, whichever of them
exist — and `ObjectProvider` sidesteps the auto-configuration ordering trap that makes
`@ConditionalOnBean` on another auto-configuration's beans unreliable. Registries the context
doesn't publish are skipped; define your own `CanonicalResilience4jRegistrar` bean and the
auto-configuration backs off.
