@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.alexhumphreys.canonicallog

import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.debug.DebugProbes

/**
 * Shared DebugProbes wiring for the concurrency property specs (todo 040). [captureBaseline]
 * once per spec (Kotest itself keeps a long-lived "spec-scope" coroutine alive for the whole
 * test — that's the interpreter, not a leak, so it must be excluded rather than re-detected on
 * every iteration). Then call [assertNoLeakedCoroutines] after each property iteration has
 * joined/awaited all its own work — the check is "no coroutine outlives the iteration", not "no
 * coroutine outlives the unit", so detached-launch cases must release and join their latch-gated
 * work before asserting (see HostilePlanPropertyTest's `DetachedLaunch`).
 */
internal fun installCoroutineLeakProbe() {
    if (!DebugProbes.isInstalled) DebugProbes.install()
}

internal fun uninstallCoroutineLeakProbe() {
    if (DebugProbes.isInstalled) DebugProbes.uninstall()
}

/** Jobs already alive when the spec started (Kotest's own scaffolding). */
internal fun captureBaselineJobs(): Set<Job> =
    DebugProbes.dumpCoroutinesInfo().mapNotNull { it.job }.toSet()

internal fun assertNoLeakedCoroutines(baseline: Set<Job>) {
    DebugProbes.dumpCoroutinesInfo()
        .filter { it.job != null && it.job !in baseline }
        .map { it.toString() }
        .shouldBeEmpty()
}
