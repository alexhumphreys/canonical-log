package io.github.alexhumphreys.canonicallog

import org.jetbrains.lincheck.datastructures.LongGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.StringGen
import org.jetbrains.lincheck.datastructures.Validate
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Lincheck linearizability checking for [CanonicalLogContext] (todo 033).
 *
 * This is the second deliberate exception to the Kotest-only rule (alongside
 * `JavaErgonomicsTest`): Lincheck's checker is driven from plain JUnit `@Test` methods.
 * Model checking is the primary mode (exhaustive, deterministic exploration of bounded
 * interleavings — it analyzes `ConcurrentHashMap` internals, not just our code);
 * [stressSmoke] is a real-scheduler smoke pass over the same spec.
 *
 * ## The spec: per-field value-linearizability, observed through `snapshot()`
 *
 * The strict spec (a full-map `snapshot(): Map` operation, conflict markers included,
 * `markFailed` atomic) is NOT linearizable, and deliberately so — three independent
 * weakenings were made, each confirmed by a Lincheck counterexample trace against the
 * stricter spec:
 *
 * 1. **Conflict markers are excluded from linearizable state.** `increment()` detects a
 *    type conflict inside `merge` but records the three `canonical_log_type_conflict*`
 *    fields *after* `merge` returns, so the markers trail the increment's linearization
 *    point. They are best-effort diagnostics (see `increment`'s KDoc); their contract is
 *    checked separately as a quiescent property by [validateConflictMarkers]: after every
 *    explored interleaving, the markers are either all present and coherent (flag `true`,
 *    key in the touched domain, type the conflicting class name) or all absent.
 *
 * 2. **Full-map snapshot atomicity is not claimed.** `snapshot()` copies a
 *    `ConcurrentHashMap` via its weakly-consistent iterator, so a copy taken *while
 *    writers are still active* can observe one racing multi-field write and miss another
 *    (Lincheck's found trace: `markFailed` racing `snapshot` yields `{error=true}` with
 *    `error_reason` absent). Making the copy point-atomic would require locking every
 *    write, undoing the documented lock-free concurrency model, to strengthen a window
 *    that real usage doesn't rely on: emit takes the snapshot after the work unit
 *    completes, and contributions racing emit are already documented as best-effort
 *    cutoff (late Kafka acks, orphaned launches). What IS guaranteed — and checked here —
 *    is that every *single field* read through the snapshot copy is linearizable: never
 *    torn, never a lost increment, real-time order respected per key. Hence the read
 *    operations below return one key's value out of a fresh `snapshot()` call (the real
 *    emit-time read path), not the whole map.
 *
 * 3. **Compound marks (`markFailed`/`markDegraded`) are not atomic pairs.** They are two
 *    independent `put`s, and Lincheck proved the pair is observably non-atomic even
 *    through per-key reads (found trace: `snapshotError()` returned `true` while a
 *    subsequent `snapshotErrorReason()` returned `null`, mid-`markFailed`) — and no write
 *    ordering fixes it, the mirrored tear just appears instead. The honest contract
 *    (KDoc'd on both methods): each field is individually last-writer-wins; only a reader
 *    racing the mark can see a partial pair, and emit snapshots after work-unit
 *    completion see both. So the spec observes `markFailed` through its `error` flag only
 *    (the flag write is the operation's linearization point); the reason field's
 *    pairing is pinned at quiescence by the existing Kotest suites.
 *
 * Domains are kept tiny on purpose (keys `a`/`b`, one string value, small Longs) —
 * Lincheck's interleaving space explodes otherwise. Default bounds are modest so the
 * regular test task stays fast; `-Dlincheck.deep=true` switches to a much deeper
 * exploration for a nightly job.
 */
@OptIn(DelicateCanonicalLogApi::class)
@Param(name = "key", gen = StringGen::class, conf = "1:ab")
@Param(name = "val", gen = LongGen::class, conf = "0:2")
class CanonicalLogContextLincheckTest {

    private val ctx = CanonicalLogContext(WorkUnit("lincheck", "test", Instant.EPOCH))

    @Operation
    fun putLong(@Param(name = "key") key: String, @Param(name = "val") value: Long) {
        ctx.put(key, value)
    }

    @Operation
    fun putString(@Param(name = "key") key: String) {
        ctx.put(key, "s")
    }

    @Operation
    fun increment(@Param(name = "key") key: String) {
        ctx.increment(key)
    }

    @Operation
    fun markFailed() {
        ctx.markFailed("boom")
    }

    /** Emit-time read of one field, through the real snapshot path. */
    @Operation
    fun snapshotValue(@Param(name = "key") key: String): Any? = ctx.snapshot()[key]

    // Deliberately no snapshotErrorReason() operation: the error/error_reason pair is a
    // non-atomic compound write (weakening 3 above); observing both fields makes the
    // mid-markFailed state visible and no sequential order can explain it.
    @Operation
    fun snapshotError(): Any? = ctx.snapshot()[CanonicalFields.ERROR]

    /**
     * Quiescent contract for the conflict markers, checked after every explored
     * interleaving: either no conflict was recorded, or all three marker fields are
     * present and coherent. Mixed `_key`/`_type` values from two racing conflicts are
     * allowed (each field is last-writer-wins), but each must individually be valid.
     */
    @Validate
    fun validateConflictMarkers() {
        val fields = ctx.snapshot()
        val flag = fields[CanonicalFields.TYPE_CONFLICT]
        val key = fields[CanonicalFields.TYPE_CONFLICT_KEY]
        val type = fields[CanonicalFields.TYPE_CONFLICT_TYPE]
        if (flag == null) {
            check(key == null && type == null) {
                "conflict key/type present without flag: key=$key type=$type"
            }
            return
        }
        check(flag == true) { "conflict flag must be true, was $flag" }
        // StringGen("1:ab") generates "", "a", and "b" (word length grows from zero).
        check(key is String && key.length <= 1 && key.all { it in 'a'..'b' }) {
            "conflict key outside key domain: $key"
        }
        check(type == "kotlin.String") { "conflict type must name the conflicting class, was $type" }
    }

    private fun <O : Options<O, *>> O.configure(): O {
        val deep = System.getProperty("lincheck.deep") == "true"
        threads(2)
        actorsPerThread(3)
        actorsBefore(1)
        actorsAfter(1)
        iterations(if (deep) 100 else 20)
        invocationsPerIteration(if (deep) 10_000 else 2_000)
        sequentialSpecification(SequentialCanonicalLog::class.java)
        return this
    }

    @Test
    fun modelChecking() {
        ModelCheckingOptions().configure().check(this::class.java)
    }

    @Test
    fun stressSmoke() {
        StressOptions().configure().check(this::class.java)
    }
}

/**
 * The sequential specification: a plain single-threaded model of the accumulator's
 * per-field value contract. `increment` on an absent key creates the counter, on a `Long`
 * adds to it, and on anything else drops the increment (the non-throwing conflict rule).
 * Conflict markers are not modelled — see the spec discussion on the test class.
 */
class SequentialCanonicalLog {
    private val fields = HashMap<String, Any>()

    fun putLong(key: String, value: Long) {
        fields[key] = value
    }

    fun putString(key: String) {
        fields[key] = "s"
    }

    fun increment(key: String) {
        when (val existing = fields[key]) {
            null -> fields[key] = 1L
            is Long -> fields[key] = existing + 1L
            else -> Unit // dropped, per the accumulator's non-throwing conflict rule
        }
    }

    fun markFailed() {
        fields[CanonicalFields.ERROR] = true
        fields[CanonicalFields.ERROR_REASON] = "boom"
    }

    fun snapshotValue(key: String): Any? = fields[key]

    fun snapshotError(): Any? = fields[CanonicalFields.ERROR]
}
