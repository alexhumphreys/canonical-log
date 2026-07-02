package io.github.alexhumphreys.canonicallog.test

import io.github.alexhumphreys.canonicallog.CanonicalLog
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class CaptureCanonicalLineTest : DescribeSpec({

    describe("captureCanonicalLineBlocking") {
        it("captures fields and result on success, contributing nothing itself") {
            val captured = captureCanonicalLineBlocking { ctx ->
                ctx.put("post_id", 42L)
                CanonicalLog.increment("cache_lookup_count")
                "ok"
            }

            captured.result shouldBe "ok"
            captured.exception.shouldBeNull()
            captured.outcome.shouldBeInstanceOf<Outcome.Completed>()
            captured["post_id"] shouldBe 42L
            captured["cache_lookup_count"] shouldBe 1L
            captured.workUnit.kind shouldBe "test"
            // The recording adapter must not add harness noise to the line.
            captured.fields.keys shouldBe setOf("post_id", "cache_lookup_count")
            currentCanonicalContext().shouldBeNull()
        }

        it("captures a block exception without rethrowing") {
            val captured = captureCanonicalLineBlocking { ctx ->
                ctx.put("before_throw", "set")
                throw IllegalStateException("boom")
            }

            captured.exception.shouldBeInstanceOf<IllegalStateException>()
            captured.exception?.message shouldBe "boom"
            val outcome = captured.outcome.shouldBeInstanceOf<Outcome.Threw>()
            outcome.cause shouldBe captured.exception
            captured["before_throw"] shouldBe "set"
            shouldThrow<IllegalStateException> { captured.result }
            currentCanonicalContext().shouldBeNull()
        }

        it("captures a marked failure as a Completed line with error fields") {
            val captured = captureCanonicalLineBlocking { ctx ->
                ctx.markFailed("post_not_found", "post_id" to 999L)
                404
            }

            captured.result shouldBe 404
            captured.outcome.shouldBeInstanceOf<Outcome.Completed>()
            captured["error"] shouldBe true
            captured["error_reason"] shouldBe "post_not_found"
            captured.fields shouldNotContainKey "error_class"
        }

        it("propagates Errors instead of capturing them") {
            shouldThrow<OutOfMemoryError> {
                captureCanonicalLineBlocking { throw OutOfMemoryError("synthetic") }
            }
            currentCanonicalContext().shouldBeNull()
        }
    }

    describe("captureCanonicalLine") {
        it("captures contributions from async children on the block's scope") {
            runTest {
                val captured = captureCanonicalLine { ctx ->
                    val child = async {
                        CanonicalLog.increment("child_count")
                        21
                    }
                    ctx.put("parent", "yes")
                    child.await() * 2
                }

                captured.result shouldBe 42
                captured.outcome.shouldBeInstanceOf<Outcome.Completed>()
                captured["child_count"] shouldBe 1L
                captured["parent"] shouldBe "yes"
                currentCanonicalContext().shouldBeNull()
            }
        }

        it("captures a block exception without rethrowing") {
            runTest {
                val captured = captureCanonicalLine { ctx ->
                    ctx.put("before_throw", "set")
                    throw IllegalArgumentException("suspend boom")
                }

                captured.exception.shouldBeInstanceOf<IllegalArgumentException>()
                captured.outcome.shouldBeInstanceOf<Outcome.Threw>()
                captured["before_throw"] shouldBe "set"
                currentCanonicalContext().shouldBeNull()
            }
        }
    }

    describe("testCanonicalLogContext + withBoundCanonicalContext") {
        it("routes ambient contributions into the test context and restores the binding") {
            val ctx = testCanonicalLogContext()

            val result = withBoundCanonicalContext(ctx) {
                CanonicalLog.put("ambient", "yes")
                CanonicalLog.increment("ambient_count")
                "done"
            }

            result shouldBe "done"
            ctx.snapshot()["ambient"] shouldBe "yes"
            ctx.snapshot()["ambient_count"] shouldBe 1L
            ctx.workUnit.id shouldBe "test-work-unit"
            ctx.workUnit.kind shouldBe "test"
            currentCanonicalContext().shouldBeNull()
        }

        it("restores the previous binding, including when the block throws") {
            val outer = testCanonicalLogContext(id = "outer")
            val inner = testCanonicalLogContext(id = "inner")

            withBoundCanonicalContext(outer) {
                shouldThrow<IllegalStateException> {
                    withBoundCanonicalContext(inner) {
                        CanonicalLog.put("where", "inner")
                        error("boom")
                    }
                }
                currentCanonicalContext() shouldBe outer
            }

            inner.snapshot()["where"] shouldBe "inner"
            currentCanonicalContext().shouldBeNull()
        }
    }
})
