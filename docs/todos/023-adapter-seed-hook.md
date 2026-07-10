# `WorkUnitAdapter.seed` — capture ambient context at work-unit open

**Status:** todo · **Modules:** `canonical-log-core` (call sites cover every entry point)
**Depends on:** nothing (independent of 020–022, but 022's adopters are the first audience).

## Problem

An adapter can only write fields in `enrich`, which runs at the **end** of the lifecycle —
and possibly on a different thread (servlet `AsyncListener`, the scheduling observation's
`onStop`). Ambient, request-scoped state that exists at the **start** on the opening thread —
an MDC `trace_id`/`request_id` put there by an upstream filter, the current OTel span id, a
tenant baggage entry — cannot be captured reliably: by enrich time the opening thread's
context is gone. Today the workarounds are bad: put it in handler code per endpoint
(boilerplate, and forgotten), or stash it somewhere in `describe` (which returns a
`WorkUnit` and has no accumulator to write to — people resort to abusing the `id`).

This is mechanism, not policy: the library provides *when* ambient capture runs; what gets
captured stays adopter code (no OTel dependency, per the anti-goals).

## Design

### 1. New default method on the SPI

```kotlin
public interface WorkUnitAdapter<T> {
    public fun describe(input: T): WorkUnit

    /**
     * Write fields that must be captured at work-unit open, on the opening thread —
     * ambient state (MDC entries, tracing ids) that may be gone by [enrich] time.
     * Called exactly once, after the context is created and nesting recorded, before
     * the work runs. Default: no-op.
     *
     * Precedence: seed runs first, so the handler and [enrich] both overwrite it for
     * the same key — seed values are defaults, not authority.
     *
     * Must not throw; as a backstop a throwing seed is swallowed, WARN-logged, and
     * recorded on the line via canonical_log_seed_error / canonical_log_seed_error_class.
     */
    public fun seed(ctx: CanonicalLogContext, input: T) {}

    public fun enrich(ctx: CanonicalLogContext, input: T, outcome: Outcome)
}
```

Default implementation keeps this non-breaking for every existing adapter (source and
binary); `-Xexplicit-api=strict` is satisfied by the explicit modifiers.

### 2. Call sites (both in `WithCanonicalLog.kt`)

Every lifecycle flows through one of two context-creation points; add a guarded seed call to
each, mirroring `runEnrich`:

- `openCanonicalWorkUnit(adapter, input)`: after `recordNesting`, before returning the scope
  (order relative to the threadlocal bind doesn't matter — pick after bind so a seed that
  logs sees `work_unit_id` in MDC, and document that choice). This covers
  `withCanonicalLogBlocking`, the servlet filter, and the scheduling observation handler
  with zero changes to any of them.
- `withCanonicalLog` (suspend): after the inline `recordNesting`, **before** `withContext` —
  so seed runs synchronously on the caller's thread, where the ambient state lives, even
  when the block immediately switches dispatchers.

New private `runSeed(adapter, ctx, input)` next to `runEnrich`, same
swallow-Exception/WARN/record shape, writing new constants:

- `CanonicalFields.SEED_ERROR` = `"canonical_log_seed_error"`
- `CanonicalFields.SEED_ERROR_CLASS` = `"canonical_log_seed_error_class"`

(Grouped with the existing `canonical_log_enrich_error*` constants; `CanonicalFieldsTest`
gets the two value pins + the no-alias check picks them up automatically.)

### 3. Documentation pattern (KDoc example on `seed`)

The composition idiom, so nobody subclasses the reference adapters:

```kotlin
class TracingHttpAdapter(
    private val delegate: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(),
) : WorkUnitAdapter<HttpExchange> by delegate {
    override fun seed(ctx: CanonicalLogContext, input: HttpExchange) {
        delegate.seed(ctx, input)
        ctx.put("trace_id", MDC.get("trace_id"))   // ambient — only valid at open time
    }
}
```

Also update the `WorkUnitAdapter` class KDoc ("adapters must not throw" paragraph now names
all three methods) and the precedence note on `enrich` (the ordering story is now seed →
handler → enrich, with enrich authoritative).

### 4. Tests

Extend `WithCanonicalLogTest` (blocking + suspend) and `CanonicalWorkUnitScopeTest`:

- seed-written fields appear in the emitted snapshot (both entry points + the open/close
  primitive).
- **Thread pin (the reason this feature exists):** in the suspend variant, an adapter whose
  `seed` records `Thread.currentThread().id` / reads a threadlocal set by the test — assert
  it ran on the caller's thread even when the block runs entirely inside
  `withContext(Dispatchers.IO)`; equivalently assert an MDC value present only on the
  calling thread is captured.
- Precedence: handler `put` under the same key overwrites the seed value; `enrich` value
  overwrites both.
- Throwing seed: block still runs, result unaffected, line emitted with
  `canonical_log_seed_error=true` + class, one WARN. Negative assertion: successful seed
  produces no `canonical_log_seed_error*` fields.
- Java ergonomics: add a case to `JavaErgonomicsTest` proving a Java `WorkUnitAdapter`
  implementation compiles without overriding `seed` (default-method interop).

### 5. Docs

`docs/CLAUDE.md`: extend the adapter-precedence gotcha entry with the seed → handler →
enrich ordering and the "seed is for ambient capture, not identity" rule (identity stays in
`describe`).

## Acceptance

- Existing adapters compile and behave unchanged (no test edits needed outside additions).
- A composed adapter's `seed` captures caller-thread MDC state that is provably absent at
  enrich time on the async path; fields land on the line.
- Failure containment matches enrich (pinned); `CanonicalFieldsTest` pins the two new
  constants.
