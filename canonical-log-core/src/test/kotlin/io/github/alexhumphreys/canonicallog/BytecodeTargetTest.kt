package io.github.alexhumphreys.canonicallog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the compiled bytecode major version so the Java 17 floor can't silently regress (todo
 * 025). The bytecode target is a compatibility contract: 17-floor consumers (Dropwizard 4,
 * pinned platform toolchains) reject major-version > 61 class files, and raising the floor
 * later is a breaking change. A class file's major version lives in bytes 6-7 (big-endian),
 * right after the 4-byte magic and the 2-byte minor version. Java 17 == 61.
 */
class BytecodeTargetTest : DescribeSpec({

    describe("library bytecode target") {
        it("compiles CanonicalLog to Java 17 (major version 61) class files") {
            val bytes = CanonicalLog::class.java.getResourceAsStream("CanonicalLog.class")
                .use { requireNotNull(it) { "CanonicalLog.class not found on classpath" }.readBytes() }

            val major = (bytes[6].toInt() and 0xFF shl 8) or (bytes[7].toInt() and 0xFF)
            major shouldBe 61
        }
    }
})
