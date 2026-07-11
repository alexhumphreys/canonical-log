# Docs: point Kafka adopters whose framework hides `ConsumerRecord` at the generic recipe

**Status:** todo · **Modules:** `canonical-log-kafka` (KDoc only), docs (recipe wording)
**Depends on:** nothing. Doc-only; no code or test changes.

*(Partially covered by the 2026-07-11 docs overhaul: `canonical-log-kafka/README.md` now
routes hidden-record adopters to the recipe. Remaining scope: the KDoc addition (§1) and
the recipe's seam-based reframing (§2).)*

## Problem

Real dogfooding feedback (2026-07-11, a Dropwizard-service integration): a Kafka listener
framework that owns the poll loop and hands consumer code only the deserialized payload +
headers never exposes `ConsumerRecord` to app code — so the shipped `WorkUnitAdapter<ConsumerRecord<*, *>>` is a dead end there. The right
answer is the generic hand-rolled `WorkUnitAdapter<T>` from
`docs/recipes/message-consumers.md`, but nothing routes a Kafka user to it:

- `KafkaRecordWorkUnitAdapter`'s KDoc covers "no loop runner ships" but not "your framework
  may not give you the record at all".
- The recipe frames the generic adapter as the fallback "for any other broker" — so a Kafka
  user's first instinct is "there's a Kafka adapter, the recipe isn't for me", and they hit
  the type mismatch before discovering the recipe.

Cost in practice: trial-and-error (reach for the concrete adapter, hit the type dead end,
then find the recipe). One or two sentences fix it.

## Design

1. **`KafkaRecordWorkUnitAdapter` KDoc** — add to the existing "no consumer loop runner
   ships here" paragraph: if your listener framework wraps the poll loop and does **not**
   expose `ConsumerRecord` to handler code (only payload/headers), this adapter cannot
   apply — hand-roll the recipe's generic `WorkUnitAdapter<T>` over whatever envelope your
   framework does hand you, keeping the same `messaging_*` field names (they're string
   literals by policy; copy the values from this adapter so lines stay queryable alongside
   direct-`ConsumerRecord` adopters).
2. **`docs/recipes/message-consumers.md`** — reword the generic-adapter framing from
   broker-based ("for any other broker") to seam-based: reach for the generic adapter when
   no shipped adapter matches your input type — a different broker, **or** a Kafka/SQS
   listener framework that hides the SDK record behind its own envelope.
3. **`docs/CLAUDE.md`** — no change needed unless the module-layout Kafka entry's wording
   is touched; if it is, keep the "literals by policy" note intact.

## Acceptance

- A Kafka adopter reading only the `KafkaRecordWorkUnitAdapter` KDoc learns, before writing
  code, that a framework-hidden `ConsumerRecord` means the generic recipe (with preserved
  `messaging_*` names), not this class.
- The recipe's generic-adapter section names the hidden-record case explicitly, not just
  "other brokers".
- No code, test, or field-contract changes; `MessageConsumerRecipeTest` untouched and green.
