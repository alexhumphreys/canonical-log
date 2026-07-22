package io.github.alexhumphreys.canonicallog

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * Keeps `docs/fields.md` honest: every `CanonicalFields` constant's wire value must appear
 * (backticked) in the field reference, so adding a field without documenting it fails here.
 * The messaging_* literals (kafka/sqs — deliberately not constants) are maintained in the
 * doc by hand; this test only guards the constants side.
 */
class CanonicalFieldsDocTest : DescribeSpec({

    describe("docs/fields.md") {
        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("docs/fields.md").toFile().exists() }
        val doc = repoRoot.resolve("docs/fields.md").toFile().readText()

        val constants = CanonicalFields::class.java.declaredFields
            .filter { it.type == String::class.java }
            .associate { it.name to it.get(null) as String }

        it("found the published constants") {
            (constants.size >= 30) shouldBe true
        }

        it("documents every CanonicalFields constant") {
            constants.forEach { (name, value) ->
                withClue("CanonicalFields.$name (\"$value\") missing from docs/fields.md") {
                    doc shouldContain "`$value`"
                }
            }
        }
    }
})
