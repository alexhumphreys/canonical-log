package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.propagatingCanonicalContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The opt-in strictness helpers (todos 044/049): a propagation mistake that production
 * deliberately swallows must become a test failure with one line of setup, and must leave the
 * process exactly as it found it.
 */
class StrictContributionsTest : DescribeSpec({

    afterEach {
        CanonicalLog.onUnboundContribution = null
        CanonicalLog.onLateWrite = null
    }

    describe("failOnUnboundContributions") {
        it("fails a body that contributes with no work unit bound, naming the key") {
            val e = shouldThrow<LostCanonicalContributionException> {
                failOnUnboundContributions {
                    CanonicalLog.put("orphan_field", "x")
                }
            }
            e.lost.shouldBeInstanceOf<LostContribution.Unbound>()
            e.lost.key shouldBe "orphan_field"
            e.message!! shouldContain "propagatingCanonicalContext"
        }

        it("passes a body whose contributions all land on the unit") {
            val line = failOnUnboundContributions {
                captureCanonicalLineBlocking {
                    CanonicalLog.put("landed", "yes")
                    CanonicalLog.increment("landed_count")
                }
            }
            line["landed"] shouldBe "yes"
            line["landed_count"] shouldBe 1L
        }

        it("fails an unwrapped thread hop and passes the same body wrapped") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                // The throw surfaces on the pool thread, so it reaches the test through the
                // Future the block joins — and captureCanonicalLineBlocking records it as the
                // block's failure rather than letting it escape the capture.
                val failed = failOnUnboundContributions {
                    captureCanonicalLineBlocking {
                        pool.submit { CanonicalLog.put("from_pool", "x") }.get(10, TimeUnit.SECONDS)
                    }
                }
                val cause = failed.exception.shouldBeInstanceOf<java.util.concurrent.ExecutionException>().cause
                cause.shouldBeInstanceOf<LostCanonicalContributionException>()
                cause.lost.key shouldBe "from_pool"

                val line = failOnUnboundContributions {
                    captureCanonicalLineBlocking {
                        pool.submit(Runnable { CanonicalLog.put("from_pool", "x") }.propagatingCanonicalContext())
                            .get(10, TimeUnit.SECONDS)
                    }
                }
                line["from_pool"] shouldBe "x"
            } finally {
                pool.shutdownNow()
            }
        }

        it("restores the previous hooks, including after a failure") {
            val sentinel: (String) -> Unit = {}
            CanonicalLog.onUnboundContribution = sentinel
            shouldThrow<LostCanonicalContributionException> {
                failOnUnboundContributions { CanonicalLog.put("orphan", "x") }
            }
            CanonicalLog.onUnboundContribution shouldBe sentinel
            CanonicalLog.onLateWrite shouldBe null
        }
    }

    describe("failOnLateWrites") {
        it("fails a write made through a captured context after the line was emitted") {
            var captured: CanonicalLogContext? = null
            val e = shouldThrow<LostCanonicalContributionException> {
                failOnLateWrites {
                    captureCanonicalLineBlocking { ctx -> captured = ctx }
                    checkNotNull(captured).put("too_late", "x")
                }
            }
            val lost = e.lost
            lost.shouldBeInstanceOf<LostContribution.LateWrite>()
            lost.key shouldBe "too_late"
            lost.workUnitId shouldBe checkNotNull(captured).workUnit.id
        }

        it("does not fire for writes made while the unit is still open") {
            val line = failOnLateWrites {
                captureCanonicalLineBlocking { ctx -> ctx.put("in_time", "x") }
            }
            line["in_time"] shouldBe "x"
        }
    }

    describe("failOnLostContributions") {
        it("covers both kinds in one wrapper") {
            shouldThrow<LostCanonicalContributionException> {
                failOnLostContributions { CanonicalLog.put("orphan", "x") }
            }
            var captured: CanonicalLogContext? = null
            shouldThrow<LostCanonicalContributionException> {
                failOnLostContributions {
                    captureCanonicalLineBlocking { ctx -> captured = ctx }
                    checkNotNull(captured).put("too_late", "x")
                }
            }
        }
    }

    describe("recordLostContributions") {
        it("collects instead of throwing, so losses on un-joined threads are still assertable") {
            var captured: CanonicalLogContext? = null
            val lost = recordLostContributions {
                CanonicalLog.increment("orphan_count")
                captureCanonicalLineBlocking { ctx -> captured = ctx }
                checkNotNull(captured).put("too_late", "x")
            }

            lost shouldContainExactly listOf(
                LostContribution.Unbound("orphan_count"),
                LostContribution.LateWrite(checkNotNull(captured).workUnit.id, "too_late"),
            )
        }

        it("returns an empty list for a clean body") {
            recordLostContributions {
                captureCanonicalLineBlocking { CanonicalLog.put("landed", "x") }
            }.shouldBeEmpty()
        }
    }
})
