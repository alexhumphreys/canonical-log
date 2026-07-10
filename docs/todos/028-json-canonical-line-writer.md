# `JsonCanonicalLineWriter` — dependency-free typed JSON sink

**Status:** todo · **Modules:** `canonical-log-core`
**Depends on:** 020 (`CanonicalLineWriter` must live in core; this lands beside
`MdcCanonicalLineWriter`).

## Problem

After 020 the sink options are: `LogstashCanonicalLineWriter` (typed fields, but requires
logstash-logback-encoder and a matching encoder config) and `MdcCanonicalLineWriter` (works
under any MDC-rendering layout, but stringifies every value — counts and durations arrive
as `"3"` and `"142"`, which breaks numeric queries and downstream schemas). There's no
option that preserves the field-type discipline **without** buying into the logstash
encoder. The gap matters for stacks whose logging pipeline is a plain pattern/console
appender per logger, or a JSON layout the library can't teach about structured arguments:
route the `"canonical"` logger to a `%msg%n` appender and you want the message itself to be
the complete, typed JSON object.

## Design

### 1. Serializer first, writer second

The deliverable is a public serializer plus a thin writer over it, both in core (zero new
dependencies — this is hand-rolled JSON, kept safe by the accumulator's own type rules):

```kotlin
/** Serialize a canonical field snapshot as one flat JSON object, keys sorted. */
public fun canonicalLineJson(fields: Map<String, Any>): String

public class JsonCanonicalLineWriter(
    loggerName: String = "canonical",
    private val emitLine: (String) -> Unit = defaultSlf4jEmit(loggerName),
) : CanonicalLineWriter {
    override fun write(context: CanonicalLogContext) = emitLine(canonicalLineJson(context.snapshot()))
}
```

- `emitLine` decouples serialization from transport: default logs the JSON string as the
  event message via `logger.info(json)` (no placeholders can fire — `{}` inside JSON string
  values is preceded by other chars, but sanity-check the SLF4J anchor rule and, if in
  doubt, use the single-arg `info(String)` overload which never substitutes). Adopters can
  pass a file/stdout/socket lambda instead. `canonicalLineJson` being public means any
  custom writer or future sink reuses the exact serialization.
- The human `message` from `canonicalLineMessage` is **not** the log message here (the JSON
  is); instead include it *inside* the object under a `"message"` key so the line stays
  human-skimmable — unless the snapshot already contains a `message` field (handler-owned;
  don't clobber — the check-before-default pattern).

### 2. Serialization rules (the type discipline, enforced)

Flat object, keys sorted lexicographically (deterministic output for tests/diffs — the
field-ordering gotcha in docs/CLAUDE.md says "if ordering matters at emit, sort there";
here it does). Value mapping:

- `String`/`CharSequence` → JSON string with full RFC 8259 escaping: `"` `\` and all
  control chars < 0x20 (`\n` `\r` `\t` named, the rest `\u00XX`). This is the one piece
  that must be property-tested, not eyeballed.
- `Long`/`Int`/`Short`/`Byte` → JSON number, unquoted.
- `Boolean` → `true`/`false`.
- `Double`/`Float` → finite values as JSON numbers; NaN/Infinity → **string** (`"NaN"`) —
  JSON has no representation and the type rules discourage floats anyway; don't throw.
- `Enum` → its `name` as a string.
- Everything else → `toString()`, escaped as a string. Lists/maps get no special handling —
  the type rules say they should never be in the accumulator; their `toString()` landing as
  an ugly string is the honest, non-throwing outcome (and a visible smell, which is a
  feature).
- Keys escape identically to string values. Nulls can't occur (`put` drops them) but guard
  anyway (skip the entry).

The serializer must never throw (writer contract): a `toString()` that throws is caught per
entry and rendered as `"<serialization_failed: FQCN>"` — telemetry never fails the emit for
one bad value.

### 3. Tests (kotest)

- Round-trip property test: generate maps of random strings (including control chars,
  quotes, backslashes, emoji/surrogates) and mixed primitive values → `canonicalLineJson`
  → parse with a real JSON parser and compare. Add a **test-scope only** Jackson dependency
  (`testImplementation("com.fasterxml.jackson.core:jackson-databind")`) as the oracle —
  the whole point is core's *main* classpath stays dependency-free; assert that in review,
  not in code.
- Determinism: same map twice → identical string; key order sorted.
- Type pins: Long stays unquoted, Boolean bare, NaN as string, enum name, list's toString
  as escaped string, throwing `toString()` → placeholder, `message` key composed unless
  already present.
- Writer: `ListAppender` on `"canonical"` → one event whose message parses as the full
  object; custom `emitLine` lambda receives the exact string.

### 4. Docs

- KDoc cross-linking the three sinks with a one-line chooser: logstash encoder configured →
  `LogstashCanonicalLineWriter`; MDC-flattening layout → `MdcCanonicalLineWriter`; dedicated
  raw appender / anything else → `JsonCanonicalLineWriter`. Put the same chooser table in
  the README sink section and `docs/CLAUDE.md`'s module layout (core entry).
- Note for the Dropwizard bundle (022): its KDoc's "typed fields" pointer should point here
  once both exist — a per-logger `%msg%n` console appender in Dropwizard config is simpler
  than a custom layout factory. If 022 already landed, update that KDoc in this item.

## Acceptance

- `canonicalLineJson` survives the round-trip property test against a real parser; core's
  main dependencies unchanged (slf4j + coroutines only).
- `JsonCanonicalLineWriter` usable as the writer for any entry point (Spring bean, 022
  bundle param, plain filter) and produces one parseable typed JSON object per work unit.
- Chooser docs in place across README/CLAUDE.md/sink KDocs.
