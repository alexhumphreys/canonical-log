package io.github.alexhumphreys.canonicallog.servlet

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PathExclusionsTest : DescribeSpec({

    describe("PathExclusions.matcher") {

        it("includes everything when the pattern list is empty") {
            val include = PathExclusions.matcher(emptyList())
            include(FakeRequest(uri = "/anything").request) shouldBe true
        }

        it("excludes an exact match, includes everything else") {
            val include = PathExclusions.matcher(listOf("/health"))
            include(FakeRequest(uri = "/health").request) shouldBe false
            include(FakeRequest(uri = "/health/deep").request) shouldBe true
            include(FakeRequest(uri = "/posts/1").request) shouldBe true
        }

        it("excludes a trailing-star prefix match") {
            val include = PathExclusions.matcher(listOf("/internal/*"))
            include(FakeRequest(uri = "/internal/metrics").request) shouldBe false
            include(FakeRequest(uri = "/internal/").request) shouldBe false
            // The prefix is the pattern minus its '*' ("/internal/"), so the bare
            // "/internal" (no trailing slash) is not covered — a deliberately simple rule.
            include(FakeRequest(uri = "/internal").request) shouldBe true
            include(FakeRequest(uri = "/public/metrics").request) shouldBe true
        }

        it("combines exact and prefix rules") {
            val include = PathExclusions.matcher(listOf("/health", "/actuator/*"))
            include(FakeRequest(uri = "/health").request) shouldBe false
            include(FakeRequest(uri = "/actuator/prometheus").request) shouldBe false
            include(FakeRequest(uri = "/posts/1").request) shouldBe true
        }
    }
})
