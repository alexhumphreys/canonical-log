# `ContributorContractTest` harness — the behavioral pin for "providers never throw"

**Status:** todo · **Modules:** `canonical-log-test`, contributor modules (adopting the
harness), docs
**Depends on:** nothing hard. Reads best after 044 (a strict-mode hook would let the
harness also assert "no unbound contribution"), but don't block on it.
**Source:** 2026-09-02 design-explainer gap review follow-up (PR #28) — promoting the
long-deferred harness from `docs/CLAUDE.md`'s testing section, whose "pending the third
contributor" extract-don't-design trigger has long since fired (Kafka, SQS, JobRunr,
Resilience4j, spring-retry all shipped).

> **Explainer note:** `docs/design-explainer.md` §5 item 4 says the adapter/contributor
> must-not-throw guards are "a backstop, not a license" with no per-contributor
> enforcement. If this lands, update it to point at the harness.

## Problem

"Adapters and contributors must not throw" is a documented contract
(`WorkUnitAdapter` KDoc, `CanonicalFields.CONTRIBUTOR_ERROR`), and the *backstop* is
behaviorally pinned (swallow-and-record, `WithCanonicalLogTest`) — but nothing enforces
the discipline per contributor. Each shipped module hand-rolls its own field assertions,
none systematically feeds adversarial inputs, and a new contributor can ship with a
throwing edge case that only ever manifests as `canonical_log_contributor_error` noise in
production. Static tools can't pin this (throwing is behavioral; ArchUnit/bytecode rules
are structurally blind to it, and Kotlin `const val` inlining defeats the
literal-vs-constant rules people usually want from ArchUnit) — it needs an execution
harness.

The negative-assertion helpers (`hasNoField`, `hasNoFieldMatching`) deferred alongside it
in `docs/CLAUDE.md` are part of the same extraction: the harness is their first shared
consumer.

## Design

### 1. Harness in `canonical-log-test`

A reusable contract every contributor suite runs, shaped as a base spec or a
function-composition helper (implementer's call — favor the shape that reads naturally in
Kotest; no assertion DSL, per the anti-goals):

```kotlin
contributorContract(
    name = "okhttp interceptor",
    contribute = { ctx -> /* drive the real instrumented operation against ctx */ },
    expectedFields = mapOf(
        CanonicalFields.HTTP_CLIENT_REQUEST_COUNT to beLong(),
        ...
    ),
    allowedFieldPrefixes = setOf("http_client_"),
)
```

Contract cases the harness generates from that description:

1. **Expected fields land** with the expected types (Long counters, integer-ms durations,
   naming conventions — `_count`/`_ms`/`_duration_ms_total` suffix checks are mechanical).
2. **Nothing leaks**: every contributed key matches `allowedFieldPrefixes` (plus the
   `canonical_log_*` diagnostics) — the field-creep negative assertion, via the extracted
   `hasNoField`/`hasNoFieldMatching` helpers, now first-class test-kit API.
3. **No throw escapes** (the headline case): the contribute step runs against adversarial
   contexts and inputs and must never propagate an `Exception` —
   - contribution with **no unit bound** (must silently no-op, not NPE);
   - a context where a target key already holds a **conflicting type** (increment-on-String
     — must drop + record, per the accumulator rule);
   - module-specific hostile inputs supplied by the caller (failed operations, null-ish
     optionals, hostile header/name values) via an `adversarialInputs` block — the harness
     provides the frame, the module supplies the hostility.
4. **Failure-path contribution**: driving the operation's failure branch still contributes
   (error counters) and still doesn't throw — the `catch (IOException)` branch class of
   bug called out in the `increment` KDoc.

Keep it mechanism-not-policy: the harness asserts the *contract* (types, prefixes,
no-throw, no-leak); which fields a contributor writes stays the module's declaration.

### 2. Adopt in shipped contributor modules

Wire the harness into the existing suites for okhttp, jdbc, kafka (producer decorator +
consumer adapter), sqs, jobrunr, resilience4j, and spring-retry — additive; existing
bespoke tests stay. Where a module's current tests already cover a case, the harness run
may be near-tautological: fine, that's what a contract is. Any case a module *can't* pass
without a fix is the payoff — fix the contributor, don't weaken the harness.

### 3. Docs

- `docs/CLAUDE.md`: move `ContributorContractTest` + negative-assertion helpers from the
  "still deferred" list to shipped; one line in the `canonical-log-test` module-layout
  entry; note in the testing section that new contributors must run the contract.
- `docs/design-explainer.md` §5 item 4: point at the harness (see the note above).
- Optional follow-on (separate todo if wanted, not this one): a Detekt rule flagging bare
  `throw` in contributor modules outside guard helpers — source-level static tripwire on
  top of the behavioral pin.

## Acceptance

- Every shipped contributor module runs the contract and passes, including the
  no-unit-bound and type-conflict adversarial cases.
- `hasNoField`/`hasNoFieldMatching` are public `canonical-log-test` API with KDoc.
- A deliberately-throwing toy contributor fails the harness's no-throw case (self-test in
  `canonical-log-test`).
- `docs/CLAUDE.md` deferred list updated; explainer §5 updated.
