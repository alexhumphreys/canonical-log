# How canonical-log works, and why it's built this way

An explainer for the core module: the design, the JVM concurrency machinery underneath
it, what could go wrong, what stops it, and the roads not taken. Written for someone who
wants to put this in production and needs to *understand* it first — including the
Kotlin/JVM concurrency background, which is introduced as needed rather than assumed.

The provider plugins (JDBC, OkHttp, Kafka, …) are deliberately boring: each one hooks a
library's native extension point and calls `CanonicalLog.increment(...)`. Everything
subtle lives in `canonical-log-core`, and that's what this document covers. It is about
1,500 lines of production code; the test suite guarding it is larger than the code.

---

## 1. The design in one page

The product is a single sentence: **one wide, structured log line per unit of work.** An
HTTP request, a Kafka message consumption, a scheduled job run — each gets exactly one
JSON line at the end, carrying everything that happened inside it.

Mechanically, that decomposes into three pieces:

**An accumulator** — [`CanonicalLogContext`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogContext.kt)
— a `ConcurrentHashMap<String, Any>` that lives for the lifetime of one work unit.
Everyone writes into it: the HTTP adapter writes `http_route`, the JDBC listener
increments `db_query_count`, your handler puts `post_id`.

**A lifecycle** — [`WithCanonicalLog.kt`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt)
— which brackets the work:

```
describe → seed → bind → [ your code runs, everyone contributes ] → enrich → unbind → emit
```

`describe` builds the unit's identity (id, kind, start time). `seed` captures ambient
state that only exists at open time on the opening thread (trace ids, MDC entries).
The block runs. `enrich` writes the mechanically-uniform end-of-unit fields (status,
duration). `unbind` restores whatever context was active before. `emit` serializes a
snapshot of the accumulator into one line.

**A propagation mechanism** — the part that makes `CanonicalLog.put("post_id", 1)` work
from *anywhere inside the request* without passing the accumulator around as a
parameter. Three lines of code deep in a repository class, four dispatcher switches away
from the controller, still land on the right request's line. This is the hard part, and
it's the subject of section 2.

The key framing: this is **a pattern with library support, not a framework**. The
accumulator is a map; contributors are ordinary interceptors that write to it; the
lifecycle is a `try`/`finally` with careful ordering. There is no agent, no bytecode
weaving, no background machinery. When you read a canonical line, every field on it was
written by an explicit `put` or `increment` you can grep for.

### Who writes what, and who wins

Three parties write to the same map, so precedence is part of the contract
([`WorkUnitAdapter`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WorkUnitAdapter.kt)):

- **seed** runs first → its values are *defaults*, anyone may overwrite.
- **the handler block** runs second → wins over seed.
- **enrich** runs last → authoritative for mechanical fields (status, durations), but
  deliberately defers to a handler-set `error_reason`/`cancel_reason` — the handler's
  *intent* fields — by checking presence before writing its default.

This is why `CanonicalLog.markFailed("post_not_found")` in a handler survives even though
the HTTP adapter also has opinions about errors: the adapter only fills `error_reason`
when nobody else did.

### The providers: three shapes, two lifecycle styles

"Deliberately boring" deserves substantiation. Every provider module in the repo is one
of three shapes, and once you can classify a module you've mostly understood it:

**Contributors** hook a library's native extension point and write fields into whatever
unit happens to be active — they never open or close anything. The
[`OkHttpCanonicalInterceptor`](../canonical-log-okhttp/src/main/kotlin/io/github/alexhumphreys/canonicallog/okhttp/OkHttpCanonicalInterceptor.kt)
is the archetype: resolve the context once (request tag first, then the thread binding —
the tag is what survives the hop to OkHttp's dispatcher threads on `enqueue()`), time
`chain.proceed()`, and `ctx?.increment(...)` a handful of counters. The `?.` is the whole
integration contract: no active unit, no-op. The JDBC listener, the Kafka producer
decorator, and the Resilience4j event subscription are the same shape against different
extension points — Resilience4j notably via *event observation* rather than wrapping, so
the library is never in the call path of a resilience decoration.

**Adapters** (`WorkUnitAdapter` implementations) don't hook anything — they're passive
translators the lifecycle calls at fixed points: `describe` (identity, at open), `seed`
(ambient capture, at open, on the opening thread), `enrich` (mechanical end-of-unit
fields, at close). One per *kind* of entry point:
[`HttpWorkUnitAdapter`](../canonical-log-servlet/src/main/kotlin/io/github/alexhumphreys/canonicallog/servlet/HttpWorkUnitAdapter.kt)
for requests,
[`JobRunrWorkUnitAdapter`](../canonical-log-jobrunr/src/main/kotlin/io/github/alexhumphreys/canonicallog/jobrunr/JobRunrWorkUnitAdapter.kt)
for job attempts, and so on.

**Entry points** own the lifecycle — they decide where a unit begins and ends, and they
come in exactly two styles, dictated by the shape of the framework hook available:

- *Closure style*, when the framework gives you one function call that brackets the whole
  unit: wrap it in `withCanonicalLogBlocking` / `withCanonicalLog` and the library
  sequences describe → seed → bind → block → enrich → unbind → emit for you. This is the
  style adopter code and simple integrations should always reach for first.
- *Open/close (callback) style*, when the framework delivers the boundaries as **separate
  callbacks** and no single closure can span them: `openCanonicalWorkUnit` returns a
  [`CanonicalWorkUnitScope`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt)
  and the integration drives the tail (`outcomeFor` → `enrich` → `unbind` → `emit`)
  itself, upholding the invariants section 3.4 describes. Two worked examples, each
  solving the split differently:
  - [`CanonicalJobServerFilter`](../canonical-log-jobrunr/src/main/kotlin/io/github/alexhumphreys/canonicallog/jobrunr/CanonicalJobServerFilter.kt)
    (JobRunr): open in `onProcessing`, finish in `onProcessingSucceeded` /
    `onProcessingFailed`. The callbacks arrive on the *same worker thread*, so the open
    scope is parked in a `ThreadLocal` between them (a field would be wrong — one filter
    instance serves all workers), with a warn-and-skip guard if the same-thread
    assumption ever breaks.
  - `runCanonicalHttpRequest` in
    [`canonical-log-servlet`](../canonical-log-servlet/src/main/kotlin/io/github/alexhumphreys/canonicallog/servlet/CanonicalLogServletFilter.kt)
    (the single copy of the HTTP lifecycle both the Spring and plain servlet filters
    call): sync requests finish inline, but an async-started request returns from the
    filter *before the handler completes*, so the terminal moves to an `AsyncListener` —
    which can fire `onComplete`/`onError`/`onTimeout` more than once and concurrently,
    hence the CAS-guarded single emit in `CanonicalLogAsyncEmitListener`. Note the
    ordering it must preserve: `unbind` happens on the request thread in `finally`
    (before the filter returns), while `enrich`/`emit` may run later on the listener's
    thread — the exact split the closure form can't express, and the reason the
    open/close style exists.

The moral for reading any provider: find which shape it is, then check it against that
shape's one contract — contributors must resolve-then-`?.` and never throw; adapters must
write through `ctx` and never throw; entry points must uphold the lifecycle invariants.
Everything else in a provider file is framework-specific plumbing.

---

## 2. The context problem — the concurrency tutorial

Everything in this section answers one question: **when code calls
`CanonicalLog.put(k, v)`, how does the library know which request it belongs to?**

### 2.1 ThreadLocal: the classic answer

A `ThreadLocal<T>` is a variable with one independent slot *per thread*. Thread A sets it
to X, thread B sets it to Y; each reads back its own value. The whole implicit-context
tradition on the JVM (transactions, security contexts, SLF4J's MDC) is built on it.

The library's entire binding state is one field
([`CanonicalLogElement.kt:7`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogElement.kt)):

```kotlin
internal val threadLocalContext: ThreadLocal<CanonicalLogContext?> = ThreadLocal()
```

and the ambient API is a null-safe read of it
([`CanonicalLog.kt`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLog.kt)):

```kotlin
public fun put(key: String, value: Any?) {
    threadLocalContext.get()?.put(key, value)
}
```

Note the `?.` — **outside a work unit, contributions are silent no-ops.** That single
decision ripples through the whole design: contributors never need to check "is a request
active?"; a JDBC listener fired by a startup health check just quietly writes nowhere.

In a thread-per-request world (classic servlet container), ThreadLocal alone would be the
entire solution: the filter sets it at request start, everything downstream runs on the
same thread and sees it, the filter clears it at the end. `withCanonicalLogBlocking`
([`WithCanonicalLog.kt:98`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt))
is exactly that story, with two disciplines layered on:

**Save-and-restore, not set-and-clear.** Binding captures the *previous* value and puts
it back on unwind:

```kotlin
val previous = threadLocalContext.get()
threadLocalContext.set(ctx)
// ... work ...
threadLocalContext.set(previous)   // NOT set(null)
```

Setting to `null` on exit would be wrong the moment work units nest (a scheduled job
that opens an inner unit, a consumer inside a request): clearing instead of restoring
would leave the *outer* unit unbound after the inner one closes. Save-and-restore makes
nesting compose for free — it's the same discipline MDC uses, and you'll see the exact
pattern four times in the codebase (blocking entry, coroutine element, executor wrappers,
emit's rebinding). Nesting semantics ("inner shadows outer") are pinned by
[`NestedWorkUnitTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/NestedWorkUnitTest.kt).

**Restore in `finally`, always.** If the handler throws, the thread goes back to the
container's pool. A pool thread with a stale binding is the nightmare scenario: the
*next request served by that thread* would write its fields into the *previous request's*
accumulator. That's the "data bleed" failure mode, and it's why every bind site in the
codebase is paired with a `finally`-guarded restore.

### 2.2 Why coroutines break ThreadLocal

A Kotlin coroutine is not a thread. It's a resumable computation that *borrows* threads
from a dispatcher: it runs on one, suspends, and may resume on a completely different
one. Consider:

```kotlin
suspend fun handler() {
    CanonicalLog.put("a", 1)          // runs on thread T1
    withContext(Dispatchers.IO) {
        CanonicalLog.put("b", 2)      // runs on thread T7 — different ThreadLocal slot!
    }
    CanonicalLog.put("c", 3)          // resumes on T1... or T3, no guarantee
}
```

With naive ThreadLocal, `"b"` vanishes (T7's slot is null → silent no-op), and `"c"` is
a coin flip. Worse: dispatcher threads are *shared* — if T7's slot happened to hold
another request's context (a leftover from a bug), `"b"` would land on a stranger's line.

### 2.3 ThreadContextElement: the coroutine-shaped fix

kotlinx.coroutines' answer is the `ThreadContextElement` interface: an element you place
in a coroutine's context that gets two callbacks — one whenever the coroutine is about to
run on a thread, one whenever it leaves that thread. The dispatcher machinery invokes
them at *every* dispatch, so the element can keep any thread-local in sync with the
coroutine's logical identity.

The library's implementation is 30 lines
([`CanonicalLogElement`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogElement.kt)):

```kotlin
override fun updateThreadContext(context: CoroutineContext): Restore {
    val previous = threadLocalContext.get()
    threadLocalContext.set(this.context)
    return Restore(previous, CanonicalLogMdc.install(this.context))
}

override fun restoreThreadContext(context: CoroutineContext, oldState: Restore) {
    threadLocalContext.set(oldState.context)
    CanonicalLogMdc.restore(oldState.mdcValue)
}
```

Same save-and-restore discipline as the blocking path, executed per dispatch by the
coroutine runtime instead of once by a `finally`. The suspend entry point
`withCanonicalLog` installs it with `withContext(CanonicalLogElement(ctx)) { ... }`
([`WithCanonicalLog.kt:214`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt)),
and from then on the ThreadLocal is correct on *whatever thread the coroutine currently
occupies* — including all its child coroutines, because coroutine context is inherited.

This is the architectural keystone: **the ThreadLocal remains the single source of truth
everywhere; the element is just the mechanism that keeps it correct under coroutines.**
Contributors (`CanonicalLog.put`, the JDBC listener, the OkHttp interceptor) never know
coroutines exist — they read a thread-local, full stop. That's why the same contributor
code works unmodified from servlet threads, coroutines, and virtual threads, and why
there are no `suspend` variants of the ambient API (pinned by
[`BridgeContractTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/BridgeContractTest.kt)).

Two subtleties in the suspend entry point are worth understanding because they're
invisible until they save you:

**The block's receiver is a `CoroutineScope`** — `block: suspend CoroutineScope.(ctx) → R`.
Without it, a bare `async { ... }` inside your handler would resolve against some *outer*
scope that doesn't carry the element, and contributions from inside that `async` would
silently vanish. With it, `async`/`launch` inherit the canonical element automatically.

**That receiver is an inner `coroutineScope`**, so children you `launch` but never join
are *awaited before the outcome is computed and the line emitted*
([`WithCanonicalLog.kt:226`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt)).
Without it, an un-joined `launch` would race the emit (contributions lost) and a failing
child would surface to the caller *after* a line already claimed `Completed`. This is
structured concurrency doing real work: "the unit isn't done until its children are"
falls out of scope structure, not bookkeeping.

### 2.4 The seams: where the two worlds meet

Real applications cross the blocking/suspend boundary constantly. Both seams below are
the same underlying problem — *the accumulator's address is stored per-thread, and your
code is about to run on a different thread* — but the hand-off mechanics differ, so each
gets its own helper.

**Blocking → suspend.** In a Spring MVC app the unit is opened by the servlet filter —
blocking code — which sets the ThreadLocal on the request thread, say `tomcat-1`. The
element from 2.3 keeps the ThreadLocal correct across dispatches, but only if a
`CanonicalLogElement` is *in the coroutine's context* — and normally `withCanonicalLog`
puts it there. Here the unit came from the blocking entry point, which only sets the
ThreadLocal; nobody created an element. So the moment your coroutine hops dispatchers,
the trail goes cold — the context exists, but it's only findable via `tomcat-1`'s
ThreadLocal, and you're not on `tomcat-1` anymore:

```kotlin
fun handle() = runBlocking {          // still on tomcat-1; filter's ThreadLocal is set
    CanonicalLog.put("a", 1)          // ✅ lands
    withContext(Dispatchers.IO) {
        CanonicalLog.put("b", 2)      // ❌ IO-pool thread — its ThreadLocal is empty
    }
}
```

`withCanonicalCoroutineContext { ... }`
([`WithCanonicalLog.kt:253`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt))
is the adapter for exactly this moment. It reads the ThreadLocal on the current thread,
wraps the context it finds in a `CanonicalLogElement`, and runs your block via
`withContext(element)` — converting the context from *thread-attached* to
*coroutine-attached* form, after which dispatcher switches inside the block propagate it.
It does **not** open a new unit: no new accumulator, no seed/enrich, no emit — the filter
opened the unit and the filter closes it; this helper only makes the existing unit
reachable from coroutine-land. If no unit is active, the block just runs and
contributions are the usual silent no-ops.

**Anything → plain thread pool.** `ExecutorService.submit`, Spring `@Async`,
`CompletableFuture.supplyAsync(…, executor)` — under the hood all of these are "hand a
task to a pool," and the coroutine cure doesn't apply: the element works because the
*coroutine runtime* invokes its callbacks on each dispatch, and a pool submission is not
a coroutine dispatch. The `Runnable` gets picked up cold by a worker thread that knows
nothing about your request. The fix is manual, which is why it's a wrapper you apply:

```kotlin
executor.submit(Runnable {
    CanonicalLog.increment("things_processed")   // ✅ with the wrapper; ❌ without
}.propagatingCanonicalContext())
```

The wrapper ([`ContextPropagation.kt`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/ContextPropagation.kt))
has two halves on two threads: **at wrap time, on the submitting thread**, it reads the
ThreadLocal and stashes the context reference inside the wrapper object — it must happen
here, because the submitting thread is the only one that knows which request the task
belongs to; **at run time, on the worker thread**, it binds the captured context (and the
MDC mirror) around the task with the usual save-and-restore-in-`finally` discipline, so
the pool thread is left clean. The context reference physically rides along inside the
wrapped `Runnable` from one thread to the other — that's the whole trick.

Crucially, **the wrapper solves reachability, not timing**: the work unit emits when your
block returns and won't wait for a task it doesn't know about. The failure mode looks
like this:

```
tomcat-1:  open unit ── submit task ── block returns ── EMIT (snapshot taken) ── line written
pool-7:                    └───────────── task runs ── increment ──→ lands in the accumulator
                                                                     …after the snapshot. Too late.
```

Nothing crashes, nothing warns — the increment writes into a map that has already been
photographed, and the field is simply absent from the line (the "snapshot cutoff",
revisited in 3.2). If a task's contribution must appear on the line, join it —
`future.get()`, a latch — before the block returns; if it's genuinely fire-and-forget,
its fields are best-effort by definition.

One mental model for both seams: the ThreadLocal is a signpost that exists on one thread
at a time. Coroutine dispatches move the signpost *automatically* — if the element is
installed, which the first seam is about. Pool hand-offs move nothing automatically, so
you smuggle the reference inside the task and plant the signpost yourself on arrival.
And in both worlds, *reaching* the accumulator and *the unit waiting for you* are
separate questions — the unit only ever waits for its own structured children.

### 2.5 MDC: the second thread-local, mirrored

SLF4J's MDC is *another* thread-local map, owned by the logging framework, whose entries
Logback can print on every ordinary log line. The library mirrors `work_unit_id` into it
([`CanonicalLogMdc`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogMdc.kt))
so that your ordinary `log.info("charging card")` lines carry the same id as the
canonical line — one equality query joins the narrative logs to the wide event.

Because MDC is a *separate* thread-local, every place that binds the canonical context
must also move the mirror, with the same save-and-restore discipline. Look back at
`CanonicalLogElement` above: the `Restore` object carries *both* the previous context and
the previous MDC value, restored together per dispatch. This is also why you don't need
`kotlinx-coroutines-slf4j`'s `MDCContext` for this key — the element already does it.

### 2.6 Virtual threads: mostly a free ride, with one trap

Virtual threads (JDK 21) are cheap threads scheduled by the JVM onto a small pool of
carrier platform threads. The good news: **each virtual thread has its own ThreadLocal
storage**, so the blocking path works unchanged — thread-per-request is back, just with
millions of cheap threads. No new mechanism needed; the ThreadLocal-based design is
virtual-thread-compatible by construction.

The trap isn't correctness, it's *lifetime and volume*: a million short-lived virtual
threads each creating ThreadLocal entries is a new stress pattern (leaks if entries
outlive the unit, memory churn). That's exactly what
[`VirtualThreadTortureTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/VirtualThreadTortureTest.kt)
exists to probe — see section 3.7.

### 2.7 The concurrent map itself

Propagation solves "which accumulator?"; there's a second, independent question: what if
*multiple threads write to the same accumulator at once*? A handler fans out three
parallel OkHttp calls; each interceptor increments `http_client_request_count`
concurrently. The answers, in
[`CanonicalLogContext`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogContext.kt):

- The map is a `ConcurrentHashMap` — individual reads/writes are thread-safe and
  lock-free.
- Counters use `ConcurrentHashMap.merge`, which retries a compare-and-swap atomically per
  key — two racing `increment("hits")` calls never lose an update. A plain
  `map[k] = (map[k] ?: 0) + 1` would be a read-modify-write race and *would* lose one.
- `increment` on a key someone `put` a String into **drops the increment and records a
  diagnostic** (`canonical_log_type_conflict*`) instead of throwing — because increments
  run inside your live DB calls and HTTP calls, and *telemetry must never fail the
  operation it observes*. You'll see that principle everywhere: throwing seed/enrich/emit
  are all swallowed, WARN-logged, and recorded on the line itself.

What the map does **not** promise: `snapshot()` is a weakly-consistent copy, not an
atomic photograph, and `markFailed` is two independent `put`s, not an atomic pair. That
sounds alarming; section 3.5 explains why it's the *correct* choice and precisely what is
and isn't guaranteed.

---

## 3. The failure catalog

Each entry: the bad thing → the mechanism that prevents (or honestly bounds) it → the
test that proves it. This is the section to reread before deploying.

### 3.1 Field bleed between requests

*The failure:* request B's `user_id` appears on request A's line. The worst observability
bug there is — the data looks plausible and is silently wrong.

*Possible causes:* a stale ThreadLocal on a pooled thread (missing restore), the
coroutine element failing to restore on suspension, or an executor wrapper leaking its
binding past the task.

*The mechanism:* every one of the four bind sites is a strict save-and-restore pair with
the restore in a `finally` (or in the element's runtime-guaranteed
`restoreThreadContext`). There is no code path that sets the ThreadLocal without
capturing what it displaced.

*The evidence:*
[`DataBleedStormTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/DataBleedStormTest.kt)
runs many units concurrently across shared pools and dispatchers, each tagging its line
with a unit-unique field (`field_<token>`), then asserts every emitted line contains
*exactly its own* tag and counter total — any bleed in any direction fails the exact-key
ownership check.
[`HostilePlanPropertyTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/HostilePlanPropertyTest.kt)
goes further: it *generates random adversarial structures* (nested units, failing
children, executor hops, detached launches, blocking↔suspend seams), computes from the
plan which keys **must**, **must not**, and **may** appear on each line, and checks the
emitted lines against that oracle. Property tests like this find the interaction bugs no
hand-written case anticipates.

### 3.2 Lost contributions after a dispatcher hop

*The failure:* `CanonicalLog.put` inside `withContext(Dispatchers.IO)` silently writes
nowhere (section 2.2's broken example).

*The mechanism:* `CanonicalLogElement`'s per-dispatch install (2.3); the scope receiver
so `async`/`launch` inherit it; `withCanonicalCoroutineContext` and
`propagatingCanonicalContext()` for the seams the element can't see (2.4).

*The evidence:*
[`BridgeContractTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/BridgeContractTest.kt)
pins each guarantee as a numbered contract: same-dispatcher baseline, `withContext`
switches, parallel `async` fan-out, `launch` without join, cancellation, the
suspend-inside-blocking seam. Read it top to bottom and you have the propagation
contract in executable form.
[`ContextPropagationTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/ContextPropagationTest.kt)
covers the executor wrappers.

*The honest limit:* work you **detach** (a fire-and-forget `launch` on some global scope,
an un-joined executor task) can still be running when the unit emits. Its contributions
are cut off at the snapshot — documented, deliberate, and pinned as the "must NOT
contain" class in `HostilePlanPropertyTest`. The alternative (emit waits for arbitrary
detached work) would let a leaked task delay a request's log line forever.

### 3.3 The line lies about the outcome

*The failure:* the handler threw but the line says `Completed`; or a child coroutine
failed after emit already happened.

*The mechanism:* the block's result is captured as a `Result` *before* outcome
classification; `enrich` receives `Outcome.Threw(cause)` / `Cancelled` / `Completed`
computed from it; and the inner `coroutineScope` guarantees children finish (or fail)
*before* classification. Cancellation is classified by exception type
(`CancellationException` → `Cancelled`, not `Threw`) so timeouts and client disconnects
don't pollute error rates, and the exception is always rethrown — telemetry observes,
never swallows
([`WithCanonicalLog.kt:391`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt)).

Note the deliberate asymmetry: the library catches `Exception`, **not** `Throwable`. An
`Error` (OOM, StackOverflow) means the JVM is dying; trying to enrich/emit on top of that
would obscure the real failure. Errors unbind and propagate.

*The evidence:* [`OutcomeMarkersTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/OutcomeMarkersTest.kt),
the cancellation cases in `BridgeContractTest`, and `HostilePlanPropertyTest`'s
requirement that deterministically-throwing plans both rethrow to the caller *and* emit a
`Threw` line.

### 3.4 Emit runs zero or two times, or observes a moving target

*The failure:* a request with no line (undebuggable) or two lines (double-counted
dashboards); or a sink serializing the map while the handler still mutates it.

*The mechanism:* on the closure entry points, ordering is structural — enrich → unbind →
emit, with unbind in a `finally` — so emit runs exactly once with **no unit bound**. That
last part is subtle and load-bearing: because the finalized unit is unbound during emit,
a sink that itself logs through a contributing appender can't recurse, and ambient writes
inside emit can't land on the line being serialized. The suspend path replicates the same
ordering explicitly (`emitFinalized`,
[`WithCanonicalLog.kt:449`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLog.kt)),
because inside `withContext` the element still has the unit bound and it must be
swapped out for the emit.

On the open/close-scope path (`CanonicalWorkUnitScope`, used by the servlet filter's
async mode), emit-exactly-once is **the caller's job**, guarded with an
`AtomicBoolean.compareAndSet` — the servlet module's `CanonicalLogAsyncEmitListener` is
the worked example, because servlet async callbacks (`onComplete`/`onTimeout`/`onError`)
genuinely can race and repeat.

*The evidence:*
[`LifecycleReentrancyTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/LifecycleReentrancyTest.kt)
pins the emit-with-nothing-bound contract (writes inside emit no-op; a unit opened inside
emit nests under the *enclosing* unit, never the finalized one);
[`CanonicalLogAsyncEmitListenerTest`](../canonical-log-servlet/src/test/kotlin/io/github/alexhumphreys/canonicallog/servlet/CanonicalLogAsyncEmitListenerTest.kt)
hammers the racing-callbacks case.

### 3.5 Torn reads, lost increments, non-atomic snapshots

This is the entry where the library's honesty matters most, and where the most
interesting test lives.

*The guarantees, precisely* (from `CanonicalLogContext`'s KDoc and the Lincheck spec):

- Every **individual field** read through a snapshot is linearizable: never torn, never
  a lost increment, real-time order respected per key.
- The **full-map snapshot is *not* point-atomic**: taken mid-flight it can see one half
  of a racing `markFailed` (`error=true` present, `error_reason` not yet).
- **`markFailed` is not an atomic pair**, and the conflict-marker diagnostics trail their
  increment.

*Why accept the weakenings rather than fix them?* Making the snapshot point-atomic would
require locking every write — undoing the lock-free model that lets contributors sit in
hot paths — to strengthen a window **real usage never observes**: emit takes its snapshot
*after* the unit completes (the `coroutineScope` join and blocking structure guarantee
quiescence), so the emit-time read always sees complete pairs. The only reader who can
see a torn pair is one racing the work itself, which nothing in the shipped codepaths
does.

*The evidence:*
[`CanonicalLogContextLincheckTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogContextLincheckTest.kt)
is worth understanding as a technique. Lincheck **model checking** doesn't run threads
and hope — it *exhaustively explores bounded interleavings* of concurrent operations
(analyzing `ConcurrentHashMap`'s internals, not just this code) and checks every outcome
against a plain sequential model (`SequentialCanonicalLog`, at the bottom of the file).
The three weaknesses above aren't guesses: each was **confirmed by a Lincheck
counterexample trace** against the stricter spec, then documented as a contract instead
of papered over. The test also carries a war story: `snapshot()` iterates the map
manually rather than using `HashMap(fields)`, because the copy constructor consults
`ConcurrentHashMap.size()`, which can transiently read 0 while entries exist — returning
an *empty snapshot of a non-empty map*, losing fields the snapshotting thread itself
wrote. Lincheck found that; the comment at
[`CanonicalLogContext.kt:119`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalLogContext.kt)
pins it.

### 3.6 Telemetry failing the operation it observes

*The failure:* your checkout request 500s because a log-enrichment adapter threw.

*The mechanism:* a single principle applied uniformly — a throwing `seed`, `enrich`,
contributor, or `emit` is swallowed, WARN-logged to the library's own logger, and
*recorded on the canonical line itself* (`canonical_log_seed_error`,
`canonical_log_enrich_error`, `canonical_log_contributor_error`); the block's result is
returned or its real exception rethrown, unaffected. A throwing emit is the one case
where the line is lost (the sink itself failed) — the WARN is the only record.

*The evidence:* the seed/enrich/emit failure cases in
[`WithCanonicalLogTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/WithCanonicalLogTest.kt).

### 3.7 Leaks under virtual-thread churn

*The failure:* accumulators outliving their unit — retained by a ThreadLocal entry, a
carrier thread, or the MDC mirror — until memory dies at production volume.

*The evidence:*
[`VirtualThreadTortureTest`](../canonical-log-core/src/test/kotlin/io/github/alexhumphreys/canonicallog/VirtualThreadTortureTest.kt)
attacks it three ways: (1) ~100k concurrent units on virtual threads — some deliberately
**pinned** to their carriers via `synchronized` + sleep, the nastiest carrier-reuse
case — with exact-ownership assertions plus a check that the shared platform pool's
threads end with *no* residual context or MDC entry; (2) a `WeakReference` probe proving
a finished unit's payload becomes garbage-collectable; (3) a 500k-unit churn soak with a
heap-growth tripwire.

---

## 4. Tradeoffs — this design vs the alternatives

### 4.1 vs MDC alone

MDC already gives you a per-thread map that Logback prints. Why not just use it? Start
with the honest concession: if all you want is half a dozen string fields stamped onto
whatever your handler already logs, MDC plus a JSON layout *is* enough, and this library
would be overhead. The case for a separate mechanism only opens up when you want the
canonical line's actual contract.

The first and largest reason is that MDC has **no lifecycle**. Nothing in it says "emit
exactly one line, at the end of this unit of work, describing how it went." Hand-rolling
that bracket is where the ten-line version stops being ten lines: it has to classify the
outcome from the block's result rather than trusting a `finally` (section 3.3),
guarantee emit runs once and only once even when servlet async callbacks fire twice, and
serialize with the unit *unbound* so a logging sink can't recurse into the line it's
writing (both section 3.4) — and decide what a nested unit means. Each of those is a
section of the failure catalog, not a detail.

Second, MDC values are **strings**. You'd lose typed counters — `increment` becomes
get-parse-add-put, non-atomic under the concurrent contributors of section 3.5 — and
typed JSON output, so every number and duration arrives at your query engine quoted.

Third, MDC does not spare you the propagation work. Section 2.5 is the evidence: MDC is
*itself* a second thread-local, so the coroutine element and the executor wrappers built
in section 2 have to carry it too, save-and-restore, at every seam. Choosing MDC alone
doesn't remove that machinery — it just means you write it for a map whose copy-on-hop
semantics belong to the logging framework rather than to you.

So the library *uses* MDC for exactly what it's good at: mirroring one correlation key
onto ordinary log lines, so the narrative logs join to the wide event.

### 4.2 vs OpenTelemetry spans

The overlapping machinery is real: OTel context propagation also solves section 2, and
span attributes also collect key-values. The differences are philosophical and
operational. A canonical line is **one row per request in your log pipeline** — queryable
with LogQL/SQL alongside your other logs, no trace backend, no sampling decision
(traces at production volume are nearly always sampled; canonical lines are cheap enough
to keep at 100%, which matters precisely for the rare weird request you need to debug).
Spans are many rows per request in a trace backend, better for *where did time go across
services*, worse for *give me every field about this request in one place*. The design
treats them as complementary: `canonical-log-tracing-otel` seeds `trace_id`/`span_id`
onto the line so each system can join to the other, and core takes no OTel dependency.

You could build canonical lines *on* OTel (a span processor that flattens the local root
span at end). You'd inherit OTel's heavyweight context machinery and its attribute-type
restrictions, and your "one line" would be coupled to trace sampling and SDK
configuration. Defensible — but a much bigger dependency for a pattern that needs a map
and a try/finally.

### 4.3 vs an immutable accumulator

A functional design would thread an immutable persistent map through the request, each
contribution returning a new version. Racelessness by construction — but it dies on the
library's core requirement: **contributors are third parties on ambient paths**. A JDBC
listener buried under your ORM can't return an updated map to anyone; it needs a mutable
sink reachable from wherever it happens to run. Mutable-map-plus-careful-propagation
*is* the shape of the problem. (A middle ground — accumulate into a thread-confined
mutable map, merge at joins — is what per-CPU counters do, but it requires knowing the
join points; arbitrary executor hops don't have them.)

### 4.4 vs emitting on a background queue

Handing snapshots to a dedicated writer thread would take serialization off the request
path. The costs: a queue to bound (drop lines or apply backpressure when full — both
worse than the ~microseconds serialization costs), ordering loss against the process's
other log output, and lines lost on crash — precisely when you want them most. And it's
unnecessary: Logback's async appender already exists at the right layer if transport
latency ever matters. Emit-inline is the simpler, more honest default.

### 4.5 vs newer JVM capabilities — could JDK 21/25 replace the machinery?

The repo targets Java 17 but is built and tested on JDK 25, so this question is live
(and `docs/CLAUDE.md` flags it as an open question). Three candidates:

**`ScopedValue` (JEP 506, final in JDK 25).** The modern replacement for the
context-propagation half of ThreadLocal: an *immutably bound* value visible for the
extent of a lexical scope:

```kotlin
val CONTEXT: ScopedValue<CanonicalLogContext> = ScopedValue.newInstance()

ScopedValue.where(CONTEXT, ctx).run {
    // CONTEXT.get() works here, and in anything called from here,
    // and — with StructuredTaskScope — in forked child tasks. Unbinding is
    // structural: impossible to leak past the scope. Cheap on virtual threads.
}
```

That structural unbinding would *delete* failure modes 3.1's "forgot to restore" cause
outright, and virtual-thread inheritance is cheaper than `InheritableThreadLocal`. So why
not?

1. **The binding is immutable per scope — but that's fine**: the *map* stays mutable
   (`ConcurrentHashMap` regardless); ScopedValue would only carry the *reference*.
   Rebinding for nested units is natural (`where(...)` again). So far so good.
2. **Coroutines are the blocker.** `ScopedValue`'s binding is tied to a stack frame's
   extent on *one thread*. A coroutine's logical lifetime spans many threads and
   interleaves with other coroutines on each. There is no `ScopedValue` analogue of
   `ThreadContextElement` — kotlinx.coroutines cannot re-bind a ScopedValue on dispatch
   (binding isn't an imperative set/restore API; that restriction is its whole safety
   story). So a ScopedValue-based core would still need the ThreadLocal + element bridge
   for every suspend path — you'd maintain **two binding mechanisms** and take the
   current one's complexity anyway.
3. **Plain executor hops** (`propagatingCanonicalContext()`) also don't map: ScopedValue
   deliberately does not propagate through arbitrary `ExecutorService.submit`, only
   through `StructuredTaskScope` forks. Spring `@Async` and friends aren't structured.
4. **Java 17 floor.** ScopedValue is final only in 25.

The honest summary: ScopedValue is the right tool for a *virtual-threads-plus-structured-
concurrency-only* world, and if this library were Loom-only it should use it. In a world
where Kotlin coroutines are a first-class consumer, ThreadLocal + `ThreadContextElement`
is not legacy — it's the only mechanism that spans both. The repo's stated plan (prototype
a ScopedValue bridge and diff it against `VirtualThreadTortureTest` before v0.2) is the
right experiment; expect it to conclude "additive option for Loom shops, not a
replacement."

**Structured concurrency (JEP 505, still preview).** `StructuredTaskScope` gives Java the
guarantee Kotlin's `coroutineScope` gives this library already (section 2.3's "children
finish before emit"). When it lands stable, a Java-first adopter could get the same
children-complete-before-emit property; the library's design is already shaped for it,
since ScopedValue + STS inheritance would cover the fork case. Nothing to adopt today —
preview APIs can't ship in a Java 17-bytecode library.

**Virtual threads themselves** are not an alternative but a *validation*: they make
thread-per-request viable again, which makes the plain ThreadLocal path — the simplest
code in the library — the main path for a growing share of deployments. The design bet
("keep the ThreadLocal at the center, bridge everything else to it") gets *stronger* as
virtual threads spread, not weaker.

### 4.6 Smaller decisions worth knowing about

- **Hand-rolled JSON writer**
  ([`JsonCanonicalLineWriter`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/JsonCanonicalLineWriter.kt)):
  no Jackson/kotlinx-serialization dependency for a flat map of primitives. Kept safe by
  the accumulator's type discipline; full RFC 8259 escaping; never throws (a throwing
  `toString()` renders as a `<serialization_failed: …>` string). The tradeoff is
  maintaining ~90 lines of escaping code — tested directly, including concurrently.
- **String keys, constants file, no schema**
  ([`CanonicalFields`](../canonical-log-core/src/main/kotlin/io/github/alexhumphreys/canonicallog/CanonicalFields.kt)):
  a registry/validation DSL is an explicit anti-goal; the constants make renames a
  compile error, and everything else is operator discipline. Wide-events flexibility
  over schema safety, consciously.
- **Two-axis outcome model**: lifecycle outcome (`Completed`/`Threw`/`Cancelled` — facts
  the library observes) is orthogonal to semantic outcome (`markFailed`/`markDegraded` —
  intent the handler declares). A 404 is `Completed` + `error_reason=post_not_found`,
  no exception anywhere. `error_reason` *without* `error_class` is the query-side signal
  of a marked (vs thrown) failure.
- **Cancellation is not an error**: `cancelled=true`, never `error=true` — client
  disconnects must not pollute error-rate dashboards.
- **The handler-ownable `message` key is a constant** (`CanonicalFields.MESSAGE`):
  `JsonCanonicalLineWriter` folds the human summary in under it unless the snapshot
  already carries one, so every field the library writes stays a constant.

---

## 5. What would break it — the assumptions your code must uphold

The tests prove the library correct *under its documented contract*. Production
confidence means knowing the contract's edges, because these are the things **your**
code can do to violate them:

1. **Detached work races the emit.** Fire-and-forget (`GlobalScope.launch`, un-joined
   pool tasks) contributions may or may not land — snapshot cutoff, silently. If a field
   must appear, join before the block returns. Audit for `GlobalScope` and un-awaited
   `@Async` in code that contributes fields.
2. **Custom executor paths need the wrapper — at the right place.** The coroutine element
   covers coroutines; plain pools need `propagatingCanonicalContext()` *on the submitting
   thread where the unit is active*. Wrapping a pool whose submissions happen on
   library-owned threads does nothing (the OkHttp `enqueue()` case — its dispatcher
   promotes queued calls from OkHttp's own threads; that's why the OkHttp module has a
   request-tag mechanism instead).
3. **The open/close scope shifts invariants to you.** `openCanonicalWorkUnit` /
   `CanonicalWorkUnitScope` requires: unbind exactly once, in a `finally`, *on the
   opening thread*; enrich before emit, each at most once; emit-exactly-once guarded by
   CAS if your terminal callbacks can race. Use the closure forms unless you genuinely
   can't; if you write a new integration, crib from `runCanonicalHttpRequest` in
   `canonical-log-servlet`.
4. **Adapters and contributors must not throw** — the guards are a backstop that records
   the failure, not a license. And a throwing **emit** loses the line entirely (one WARN
   remains). Watch the `io.github.alexhumphreys.canonicallog` logger in production; those
   WARNs are your telemetry's own error channel.
5. **Type discipline per key**: an incremented key must only ever be incremented; keys
   hold primitives/strings, never collections. Violations don't throw — they surface as
   `canonical_log_type_conflict` fields or ugly `toString()` strings on the line. Both
   are queryable smells; alert on them.
6. **Don't contribute from a sink**, and treat `CanonicalLogMdc.enabled` as
   set-at-startup-only.
7. **`withCanonicalLogBlocking` around dispatcher-switching coroutine code is undefined**
   for the inner switches — the documented wrong tool. Suspend code gets
   `withCanonicalLog` or `withCanonicalCoroutineContext`.

None of these fail loudly. That's the honest cost of "telemetry never fails the
operation": the failure mode of misuse is a quietly thinner or oddly-shaped line, so the
diagnostics fields in item 5 and the WARN channel in item 4 are worth wiring into a
dashboard from day one.

---

## 6. Check your understanding

Answers at the bottom. If you can answer all five cold, you understand this library
better than most people understand their logging stack.

**Q1.** A handler does `withContext(Dispatchers.IO) { CanonicalLog.put("rows", n) }`.
Which exact mechanism makes that put land on the right line, and what are the two
callbacks involved?

**Q2.** Inside `withCanonicalLog`'s block you call `launch { delay(50); CanonicalLog.increment("x") }`
on the block's receiver and return without joining. Does `x` make it onto the line? Would
it if the `launch` were on an injected application scope instead?

**Q3.** Why does `snapshot()` hand-iterate the map instead of `HashMap(fields)`, and what
class of tool found the reason?

**Q4.** Your emit sink logs through an appender that itself calls `CanonicalLog.put`.
Why doesn't this recurse or corrupt the line being emitted?

**Q5.** Why can't `ScopedValue` simply replace the ThreadLocal today, given the repo
already builds on JDK 25?

---

*Answers.*
**A1.** `CanonicalLogElement`, a `ThreadContextElement` installed by `withCanonicalLog`'s
`withContext`. The dispatcher invokes `updateThreadContext` when the coroutine lands on
the IO thread (saving that thread's previous binding, setting the ThreadLocal + MDC
mirror) and `restoreThreadContext` when it leaves (putting both back).
**A2.** Yes: the receiver is an inner `coroutineScope`, which awaits all children before
the outcome is computed and the line emitted. On an external application scope the
coroutine is detached — the emit doesn't wait, and the increment lands after the
snapshot cutoff: absent from the line.
**A3.** `HashMap`'s copy constructor consults `ConcurrentHashMap.size()`, which can
transiently read 0 while entries exist, yielding an empty copy of a non-empty map — even
dropping the snapshotting thread's own writes. Found by Lincheck model checking
(`CanonicalLogContextLincheckTest`), which explores bounded interleavings exhaustively,
including the map's internals.
**A4.** On every entry point, emit runs with the finalized unit *unbound* (blocking path:
unbind precedes emit; suspend path: `emitFinalized` temporarily installs the enclosing
binding). The appender's `put` resolves the ThreadLocal to `null` (or an enclosing unit)
and no-ops relative to the line being serialized. Pinned by `LifecycleReentrancyTest`.
**A5.** Kotlin coroutines. `ScopedValue`'s binding is lexically scoped to one thread's
stack; kotlinx.coroutines has no way to re-establish such a binding on each dispatch
(no `ThreadContextElement` equivalent exists for it, by design — imperative rebinding
would break its safety model). A ScopedValue core would still need the entire
ThreadLocal + element bridge for suspend paths, plus it can't cross plain executor hops
and isn't in the Java 17 floor. It becomes attractive only for a Loom-only,
coroutine-free deployment — as an *additional* binding mode, which is the repo's stated
v0.2 experiment.
