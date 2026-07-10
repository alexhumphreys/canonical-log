package io.github.alexhumphreys.canonicallog

/**
 * Translates between an entry-point's input type and the canonical work-unit lifecycle.
 *
 * One implementation per kind of entry point — HTTP requests, Kafka messages, scheduled
 * jobs, etc. The implementation captures what's mechanically uniform across every
 * invocation of that entry point: a request's method/route/status, a message's
 * topic/partition, and so on. Per-operation values (`post_id`, `comment_count`) are
 * the handler's job, not the adapter's.
 *
 * **Adapters must not throw.** [describe], [seed], and [enrich] are all called by
 * `withCanonicalLog{,Blocking}` as part of the work-unit lifecycle, and a throwing
 * adapter is a bug. The library is defensive against it (a throwing [seed] or [enrich]
 * records `canonical_log_seed_error` / `canonical_log_enrich_error` on the canonical line
 * and logs a WARN; the block's result is never replaced), but treat that as a backstop,
 * not a contract — adapter implementations should read inputs that are guaranteed-valid by
 * their caller and write fields that are guaranteed-formattable.
 */
public interface WorkUnitAdapter<T> {
    /**
     * Build the [WorkUnit] identity for this invocation. Called once at the start of
     * the lifecycle. Should not have side effects beyond reading [input].
     */
    public fun describe(input: T): WorkUnit

    /**
     * Write fields that must be captured at work-unit *open*, on the *opening thread* —
     * ambient, request-scoped state (MDC entries such as a `trace_id`/`request_id`, the
     * current tracing span id, tenant baggage) that may be gone by [enrich] time. [enrich]
     * runs at the *end* of the lifecycle, possibly on a different thread (a servlet
     * `AsyncListener`, a scheduling observation's `onStop`), where the opening thread's
     * ambient context no longer exists; [seed] is the hook that runs early enough to read it.
     *
     * Called exactly once, after the context is created and nesting recorded and the
     * threadlocal is bound (so a seed that itself logs sees `work_unit_id` in MDC), before
     * the work runs. On the suspend path it runs synchronously on the caller's thread — where
     * the ambient state lives — even when the block immediately switches dispatchers. Default:
     * no-op, so every existing adapter stays source- and binary-compatible.
     *
     * **Precedence — seed values are defaults, not authority.** [seed] runs *first*, so both
     * the handler block and [enrich] overwrite it for the same key (ordering is
     * seed → handler → enrich, with [enrich] authoritative). Use it to capture ambient state,
     * not to establish identity — identity belongs in [describe].
     *
     * **This is mechanism, not policy.** The library provides *when* ambient capture runs;
     * *what* gets captured stays adopter code (no tracing-vendor dependency in core). Compose,
     * don't subclass, the reference adapters:
     * ```
     * class TracingHttpAdapter(
     *     private val delegate: WorkUnitAdapter<HttpExchange> = HttpWorkUnitAdapter(),
     * ) : WorkUnitAdapter<HttpExchange> by delegate {
     *     override fun seed(ctx: CanonicalLogContext, input: HttpExchange) {
     *         delegate.seed(ctx, input)
     *         ctx.put("trace_id", MDC.get("trace_id"))   // ambient — only valid at open time
     *     }
     * }
     * ```
     *
     * **Write through [ctx], not [CanonicalLog].** [seed] runs with the unit bound, so
     * ambient writes ([CanonicalLog.put]) do land — but that's an implementation detail of
     * when the hook runs, not a contract; `ctx.put` is the supported form. A work unit
     * opened (and completed) inside [seed] behaves as ordinary nesting: it records this
     * unit as its parent and emits its own line. Pinned by `LifecycleReentrancyTest`.
     *
     * **Must not throw** (see the class KDoc). As a backstop a throwing [seed] is swallowed,
     * one WARN is logged, and the failure is recorded on the line via
     * [CanonicalFields.SEED_ERROR] / [CanonicalFields.SEED_ERROR_CLASS] — the block still runs
     * and its result is unaffected. Default: no-op.
     */
    public fun seed(ctx: CanonicalLogContext, input: T) {}

    /**
     * Write the adapter's mechanically-uniform fields onto the [ctx]. Called once at
     * the end of the lifecycle, after the body has run. Has access to the original
     * [input] and the [outcome] (lifecycle-level success or thrown exception).
     *
     * **Precedence — the adapter wins for the same key.** The write order for a given key is
     * [seed] (at open) → handler block → `enrich`, with `enrich` authoritative: because it runs
     * *after* the handler block, an unconditional `ctx.put` here overwrites any value the handler
     * or [seed] set under the same key. That is intentional for the mechanically-uniform fields
     * (status, durations, [WorkUnit] identity): the adapter is authoritative. The
     * deliberate exceptions are the two "intent" fields — [CanonicalFields.ERROR_REASON]
     * and [CanonicalFields.CANCEL_REASON] — where a handler-set value expresses intent the
     * adapter shouldn't clobber; the reference adapter checks `ctx.snapshot()[key] == null`
     * before writing its own default (`"exception"` / `"server_error"` / `"cancelled"`).
     * Follow the same check-before-default pattern in custom adapters for any field a
     * handler is expected to own. Reference the [CanonicalFields] constants rather than
     * string literals so a rename is a compile error.
     *
     * **Write through [ctx], not [CanonicalLog].** Whether the unit is still *bound* when
     * `enrich` runs is entry-point-dependent: the closure entry points run it bound, but
     * on the open/close-scope path (`CanonicalWorkUnitScope`) it may run after `unbind()`,
     * on another thread — where ambient writes silently no-op. `ctx.put` always lands.
     * A work unit opened inside `enrich` nests under this unit only where the binding is
     * still active. Pinned by `LifecycleReentrancyTest`.
     */
    public fun enrich(ctx: CanonicalLogContext, input: T, outcome: Outcome)
}
