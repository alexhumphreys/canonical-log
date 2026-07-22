# Sinks (writers)

The canonical line is emitted through a `CanonicalLineWriter`. Which one you want depends on
how your logging backend renders structured fields. Provide a bean (Spring) or pass an
instance (Dropwizard bundle, hand-rolled entry points) to override the default. **Never wire
more than one writer for the same line.**

| Your layout | Writer | Module | Fields |
| --- | --- | --- | --- |
| logstash-logback-encoder configured | `LogstashCanonicalLineWriter` (Spring default) | `canonical-log-logstash` | typed, via `StructuredArguments` |
| JSON layout that flattens MDC but not `StructuredArguments` (e.g. Dropwizard `EventJsonLayout`) | `MdcCanonicalLineWriter` (Dropwizard default) | `canonical-log-core` | stringified (MDC is `String`-valued) |
| dedicated raw (`%msg%n`) appender / anything else | `JsonCanonicalLineWriter` | `canonical-log-core` | typed, message **is** a hand-rolled JSON object |

## `LogstashCanonicalLineWriter`

Logs the field snapshot as logstash `StructuredArguments` on the `"canonical"` logger,
preserving field types (counts stay numbers). The encoder is an `implementation` dependency
of `canonical-log-logstash` and never appears in public signatures. Takes an overridable
`message: (Map) -> String` (default: core's `canonicalLineMessage`, e.g.
`GET /posts/{id} 200 12ms`).

## `MdcCanonicalLineWriter`

Flattens the snapshot into slf4j MDC for one event, stringifying every value. For layouts
that render MDC but not `StructuredArguments`. Trade-off: counts and durations arrive as
strings — if you want typed fields under Dropwizard, pass a `LogstashCanonicalLineWriter`
and route the `"canonical"` logger to a logstash-encoder appender instead.

## `JsonCanonicalLineWriter`

Dependency-free typed sink: the log event's message is the complete JSON object (keys
sorted, own RFC-8259 escaping, `Long`/`Boolean` bare, `NaN`/`Infinity` as strings, enums as
`name`, everything else `toString()`-as-string; a throwing `toString()` is caught per-entry
as `"<serialization_failed: FQCN>"` — the writer never throws). The human summary is folded
inside the object under a `message` key (check-before-default: a handler-set `message`
survives). An `emitLine` lambda decouples transport — default `logger.info(String)` (the
single-arg overload, so `{` in values is safe); pass your own to write to a file, stdout, or
a socket. Its serializer `canonicalLineJson(fields)` is public for custom sinks.

## Custom sinks and sampling

Implement `CanonicalLineWriter` (one method: `write(fields)`) — serialize `snapshot()`,
never hold the live map. A `CanonicalLogSampler` bean (Spring) is the emit predicate,
consulted *after* enrich so it sees status/duration/error — "always keep errors, sample
healthy 200s at 1%" is expressible; a throwing sampler fails open. Writer/sampler failures
are WARN-logged and never fail the request.
