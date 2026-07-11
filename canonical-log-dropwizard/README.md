# canonical-log-dropwizard

One canonical line per request for a Dropwizard 4 service (Jersey on Jetty), from a single
bundle:

```kotlin
dependencies {
    implementation("io.github.alexhumphreys:canonical-log-dropwizard:0.1.0-SNAPSHOT")
}
```

```kotlin
class MyApplication : Application<MyConfig>() {
    override fun initialize(bootstrap: Bootstrap<MyConfig>) {
        bootstrap.addBundle(CanonicalLogBundle())
        // Exclude app-port health probes: CanonicalLogBundle(excludePaths = listOf("/ping"))
    }
}
```

The bundle installs the [servlet filter](../canonical-log-servlet/README.md) on the
**application** port (one line per request, with the matched Jersey route template as
`http_route` via a post-matching capture filter) and nothing on the **admin** port —
healthchecks and metrics never open a work unit. Contribute business fields from resources
with `CanonicalLog.put(...)` / `markFailed(...)` as usual; `work_unit_id` MDC correlation on
handler logs works with no extra wiring.

Built against Dropwizard 4.0.x (`jakarta` namespace, `compileOnly` — your Dropwizard wins at
runtime). Dropwizard 2/3 (`javax.*`) is not supported.

## Output

The default writer is `MdcCanonicalLineWriter`, because Dropwizard's stock `EventJsonLayout`
renders MDC but not logstash structured arguments. Turn on `flattenMdc` so the fields become
top-level JSON keys:

```yaml
logging:
  appenders:
    - type: console
      layout:
        type: json
        flattenMdc: true
```

MDC is string-valued, so counts/durations arrive as strings. For typed fields, pass a
`LogstashCanonicalLineWriter` to the bundle and route the `canonical` logger to a
logstash-encoder appender — see the [sink chooser](../docs/sinks.md).

## Error semantics with default exception mappers

With Dropwizard's default exception mappers on, Jersey maps an uncaught resource exception
to a 500 *before* the filter unwinds, so the line gets `error=true` /
`error_reason=server_error` but no `error_class`. Only if the throw reaches the filter
(mappers disabled) does the line carry `error_class`. Adapter, writer, sampler, and
`excludePaths` are all constructor parameters on the bundle.
