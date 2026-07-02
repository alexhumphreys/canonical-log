# Split the logstash default writer into an optional module

**Status:** todo · **Modules:** `canonical-log-spring-boot-starter`, new `canonical-log-logback` (name TBD)
**Depends on:** 001 (the `CanonicalLineWriter` seam must exist first)

## Problem

The umbrella starter has a hard `implementation(libs.logstash.logback.encoder)` dependency, so
every adopter pulls logstash-logback-encoder even if they use log4j2 or their own sink. Once
001 lands, the default writer is the only thing needing it.

## Design (sketch)

- Move `LogstashCanonicalLineWriter` to a small module; starter keeps it as a default via
  optional/`compileOnly` + `@ConditionalOnClass(StructuredArguments::class)` auto-config
  providing the default writer bean only when the encoder is on the classpath.
- Fallback when absent: a plain-slf4j writer (message-only, e.g. key=value pairs appended) or
  a startup warning telling the adopter to provide a `CanonicalLineWriter` bean — decide.
- Weigh against module proliferation (already 6 modules): an acceptable middle ground is
  keeping the dependency but marking it non-transitive and conditional as above, without a new
  module. Evaluate both at implementation time.

## Acceptance

Starter without logstash on the classpath boots and either logs usable lines or fails with an
actionable message (no `ClassNotFoundException` from the filter); with logstash present,
behaviour unchanged.
