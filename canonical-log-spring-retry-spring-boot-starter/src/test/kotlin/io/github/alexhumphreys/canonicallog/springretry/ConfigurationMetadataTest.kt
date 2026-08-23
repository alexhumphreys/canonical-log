package io.github.alexhumphreys.canonicallog.springretry

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The sample app's `ConfigurationMetadataTest` guards the starters on *its* classpath; this
 * module isn't one of them (a sample dependency with no sample usage would be noise), so the
 * same guard lives here: a stray comma in the hand-written metadata silently kills IDE
 * autocomplete, and nothing else would catch it.
 */
class ConfigurationMetadataTest : DescribeSpec({

    it("describes canonical-log.spring-retry.enabled with valid JSON, a type and a description") {
        val json = javaClass.classLoader
            .getResource("META-INF/spring-configuration-metadata.json")
            ?.readText()
            ?: error("no spring-configuration-metadata.json is shipped")

        // Deliberately not a JSON library: this module's only test-scope JSON need is this
        // check, and the assertions below are about content, not shape.
        json.trim().startsWith("{") shouldBe true
        json.trim().endsWith("}") shouldBe true
        json shouldContain "\"canonical-log.spring-retry.enabled\""
        json shouldContain "\"type\": \"java.lang.Boolean\""
        json shouldContain "\"description\""
        json shouldContain "\"defaultValue\": true"
    }
})
