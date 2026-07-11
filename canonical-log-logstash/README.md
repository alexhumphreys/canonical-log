# canonical-log-logstash

`LogstashCanonicalLineWriter` — the default sink when logstash-logback-encoder is your
layout. Logs the canonical field snapshot as typed `StructuredArguments` on the
`"canonical"` logger; the encoder stays an `implementation` dependency and never appears in
public signatures.

Split from the Spring starter so every integration (Spring, Dropwizard, servlet, scheduling,
hand-rolled entry points) can share the one writer: in Spring, a single user
`CanonicalLineWriter` bean overrides the sink for all lines.

Constructor takes an overridable `message: (Map) -> String` (default: core's
`canonicalLineMessage`, e.g. `GET /posts/{id} 200 12ms`).

If your layout doesn't use the encoder, don't depend on this module — see the
[sink chooser](../docs/sinks.md) for `MdcCanonicalLineWriter` / `JsonCanonicalLineWriter`
in core.
