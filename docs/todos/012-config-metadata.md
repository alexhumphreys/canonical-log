# Spring configuration metadata for IDE autocomplete

**Status:** todo · **Modules:** all three `*-spring-boot-starter` modules
**Depends on:** 002 (introduces the `@ConfigurationProperties` class this would annotate)

## Problem

`canonical-log.http.enabled`, `canonical-log.jdbc.enabled`, `canonical-log.okhttp.enabled`
(and `exclude-paths` once 002 lands) have no `spring-configuration-metadata.json`, so IDEs
offer no autocomplete/docs for them. Small polish, disproportionate first-impression value.

## Design

- Option A (preferred if 002's `@ConfigurationProperties` classes exist): add
  `kapt("org.springframework.boot:spring-boot-configuration-processor")` — note this brings
  kapt into the build (evaluate KSP support status at implementation time) — and write
  KDoc on each property (the processor lifts it into the metadata).
- Option B (no build-plugin cost): hand-write
  `src/main/resources/META-INF/spring-configuration-metadata.json` for the handful of keys.
  With <10 properties this is honestly fine and zero build complexity — start here, switch to
  the processor when the property count grows.

## Acceptance

`additional-spring-configuration-metadata.json`/generated metadata present in each starter
jar; keys, types, defaults, and descriptions match reality (`enabled` defaults true,
`matchIfMissing = true` behaviour described).
