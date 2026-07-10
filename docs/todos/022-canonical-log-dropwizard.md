# `canonical-log-dropwizard` — Dropwizard bundle + Jersey route capture

**Status:** todo · **Modules:** new `canonical-log-dropwizard`
**Depends on:** 020 (writer in core, `MdcCanonicalLineWriter`), 021 (`canonical-log-servlet`).

## Problem

Dropwizard services (Jersey resources on Jetty) are a natural adopter profile — one wide
line per request fits their ops model — but today they get nothing: the servlet lifecycle is
Spring-locked (fixed by 021), the matched Jersey route template (`/posts/{id}`) is not
visible at the servlet layer, and the default logstash sink produces field-less lines under
Dropwizard's `EventJsonLayout` (fixed by 020's `MdcCanonicalLineWriter`). This module is the
last mile: one bundle that wires all three.

## Design

### 1. New module

- `settings.gradle.kts`: add `"canonical-log-dropwizard"`.
- `build.gradle.kts`:
  - `api(project(":canonical-log-servlet"))` (brings core transitively).
  - `compileOnly(libs.dropwizard.core)` — new catalog entries `dropwizard = "4.0.16"`
    (use the latest 4.0.x at implementation time) and
    `dropwizard-core = { module = "io.dropwizard:dropwizard-core", version.ref = "dropwizard" }`.
    compileOnly so the adopter's own Dropwizard version wins at runtime; document "built
    against 4.0.x (jakarta namespace); Dropwizard 2/3 (`javax.*`) is not supported". Verify
    the Jersey types used below are on the compile classpath via dropwizard-core's
    transitives; if compileOnly doesn't surface them, add an explicit
    `compileOnly("org.glassfish.jersey.core:jersey-server")` aligned to Dropwizard's version.
  - Tests: `testImplementation(libs.dropwizard.testing)` (same version ref),
    `logback-classic`.
- Package: `io.github.alexhumphreys.canonicallog.dropwizard`.

### 2. `JerseyRouteCaptureFilter`

A post-matching Jersey filter whose only job is publishing the matched route template to the
servlet layer:

```kotlin
@Provider
public class JerseyRouteCaptureFilter @Inject constructor(
    private val servletRequest: jakarta.inject.Provider<HttpServletRequest>,
) : ContainerRequestFilter {
    override fun filter(requestContext: ContainerRequestContext) { ... }
}
```

- Deliberately **not** `@PreMatching` — it must run after resource matching so
  `ExtendedUriInfo.getMatchedTemplates()` is populated. Any post-matching priority is fine.
- Body: cast `requestContext.uriInfo` to `ExtendedUriInfo`; `matchedTemplates` returns the
  templates most-specific-first, so the full route is
  `matchedTemplates.reversed().joinToString("") { it.template }` with duplicate-slash
  normalization (`"//" → "/"`). **Verify the ordering empirically with a sub-resource in the
  e2e test** — this is the one Jersey behaviour worth pinning rather than trusting.
- Writes the result to `servletRequest.get().setAttribute(HttpWorkUnitAdapter.ROUTE_ATTRIBUTE, route)`.
  `HttpWorkUnitAdapter`'s default `routeResolver` (021) picks it up at enrich time — no
  other coupling.
- Entire body wrapped in try/catch-Exception → WARN on the
  `io.github.alexhumphreys.canonicallog` logger: route capture is telemetry; a non-servlet
  deployment or an injection failure must never 500 a request. A request that never reaches
  matching (404) simply has no attribute → `http_route` omitted, matching the Spring
  behaviour.

### 3. `CanonicalLogBundle`

```kotlin
public class CanonicalLogBundle @JvmOverloads constructor(
    private val adapter: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(),
    private val writer: CanonicalLineWriter = MdcCanonicalLineWriter(),
    private val sampler: CanonicalLogSampler = CanonicalLogSampler { true },
    private val excludePaths: List<String> = emptyList(),
) : ConfiguredBundle<Configuration>
```

Constructor with defaults, not a builder (see the anti-goals list — constructors are fine).
`run(configuration, environment)`:

1. `environment.servlets().addFilter("canonical-log", CanonicalLogServletFilter(adapter,
   writer, PathExclusions.matcher(excludePaths), sampler))
   .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*")` — REQUEST
   dispatch only; the filter's own once-per-request guard covers container re-dispatches.
2. `environment.jersey().register(JerseyRouteCaptureFilter::class.java)`.
3. Nothing on the admin environment — admin-port traffic (healthchecks, metrics) never
   opens a work unit for free. Document that health endpoints served on the *application*
   port need `excludePaths`.

`initialize(bootstrap)` is a no-op. `CanonicalLogMdc` correlation works with no extra
wiring (the servlet filter installs it); the process-wide `CanonicalLogMdc.enabled` switch
remains the opt-out — mention it in the KDoc rather than adding a bundle flag.

Why `MdcCanonicalLineWriter` as the default: Dropwizard's stock JSON layout is
`EventJsonLayout`, which renders MDC but not logstash `StructuredArguments`. Document the
matching config prominently in the module KDoc + README section:

```yaml
logging:
  appenders:
    - type: console
      layout:
        type: json
        flattenMdc: true   # canonical fields become top-level JSON keys
```

with the caveat that MDC stringifies values; adopters who want typed fields should pass
`LogstashCanonicalLineWriter` (dep on `canonical-log-logstash`) and route the `"canonical"`
logger to a logstash-encoder appender — pointer only, not this module's problem.

### 4. Tests

Kotest throughout, using `DropwizardTestSupport` directly (plain `before()`/`after()` calls
in the spec lifecycle — the JUnit-flavoured `DropwizardAppExtension` doesn't fit kotest).
Test app in test sources: minimal `Application<Configuration>` + one resource with
`/posts/{id}` (success, `markFailed` 404 branch, and a throwing endpoint) plus a
sub-resource locator to pin template reconstruction.

- E2e (`CanonicalLogBundleTest`): boot the support, attach a `ListAppender` to the
  `"canonical"` logger **after** boot, hit endpoints over real HTTP
  (`client.target("http://localhost:${support.localPort}")`):
  - success → exactly one line; `http_route="/posts/{id}"`, `url_path="/posts/7"`,
    `http_request_method`, `http_response_status_code=200`, duration present.
  - sub-resource route reconstructed correctly (the ordering pin from §2).
  - thrown exception → `error=true`, `error_class`, status 500, still exactly one line.
  - excluded path (`excludePaths = listOf("/ping")`) → zero lines.
  - `work_unit_id` present in the event's MDC map (correlation + MDC-writer path both
    proven by asserting fields arrive via `MDCPropertyMap`).
  - ordinary handler log line during the request carries `work_unit_id` in MDC.
- Negative assertions per house style: success lines have no `error`, no `canonical_log_*`
  self-diagnostic fields.

### 5. Docs

- `docs/CLAUDE.md` module-layout entry (mirror the level of detail of the starter entries:
  what's wired, the default writer choice and why, admin-port behaviour, opt-outs).
- README: a "Dropwizard quickstart" section — dependency coordinates,
  `bootstrap.addBundle(CanonicalLogBundle())`, the `flattenMdc` logging config, one example
  line.

## Acceptance

- A Dropwizard 4 app adds one dependency and one `addBundle` line and gets: one canonical
  line per application-port request with route template, status, duration, error/cancel
  semantics identical to the Spring starter; fields visible as top-level JSON keys under the
  stock `json` layout with `flattenMdc: true`; zero lines for admin-port and excluded-path
  traffic; `work_unit_id` MDC correlation on handler logs.
- No Spring types anywhere in the module; dropwizard-core is compileOnly.
- All assertions above pinned by the e2e spec.
