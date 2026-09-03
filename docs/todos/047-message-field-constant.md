# `CanonicalFields.MESSAGE` — constant for the handler-ownable `message` key

**Status:** todo · **Modules:** `canonical-log-core`, docs
**Depends on:** nothing.
**Source:** 2026-09-02 design-explainer write-up (`docs/design-explainer.md` §4.6) — gap review.

> **Explainer note:** minor — if this lands, no explainer change should be needed, but
> verify §4.6's "smaller decisions" bullet still reads correctly.

## Problem

`JsonCanonicalLineWriter` special-cases the `"message"` key as a string literal (`withMessage`
checks `snapshot.containsKey("message")` before folding in the human summary), and the
handler-owns-`message` contract is documented only in that writer's KDoc. This breaks the
"every field the library itself writes is a `CanonicalFields` constant" rule (todo 007):
the library *does* write `message` (the folded summary), and a handler opting to own it must
type the literal — a `put("mesage", …)` typo silently yields both fields.

## Design

- Add to `CanonicalFields`, in a small "line composition" group:

  ```kotlin
  /**
   * `String` — the human-readable summary. Written by [JsonCanonicalLineWriter] (folded
   * into the JSON object) when absent; a handler-set value wins — the same
   * check-before-default pattern as [ERROR_REASON]. The MDC/Logstash writers keep the
   * summary as the slf4j event message instead and do not write this field.
   */
  public const val MESSAGE: String = "message"
  ```

- Reference it from `JsonCanonicalLineWriter.withMessage` (and anywhere else the literal
  appears — grep `"message"` across main sources; `canonicalLineMessage` composes the value
  but shouldn't need the key).
- `CanonicalFieldsTest`: pin the constant's value and the no-aliasing rule picks it up
  automatically.
- One line in `docs/fields.md` and the `docs/CLAUDE.md` field-constants gotcha entry.

Keep scope tight: this is a constants-file addition, not a change to which writers put the
summary where.

## Acceptance

- No `"message"` string literal remains in core main sources.
- `CanonicalFieldsTest` pins `MESSAGE == "message"`.
- Handler docs (`JsonCanonicalLineWriter` KDoc) reference the constant.
