package io.github.alexhumphreys.canonicallog.sample

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * Validates the hand-written `spring-configuration-metadata.json` shipped by each starter (todo
 * 012). The sample sees all four starters on its classpath, so one test covers them: every
 * `canonical-log.*` property key that has a `@ConditionalOnProperty` / `@ConfigurationProperties`
 * behind it must be described (types/descriptions present), and every metadata file must be valid
 * JSON. A malformed file silently kills IDE autocomplete, so parsing is the guard.
 */
class ConfigurationMetadataTest : DescribeSpec({

    val mapper = ObjectMapper()

    // Every canonical-log.* key the starters actually gate on, across all four starters.
    val expectedKeys = listOf(
        "canonical-log.http.enabled",
        "canonical-log.http.exclude-paths",
        "canonical-log.http.mdc-enabled",
        "canonical-log.jdbc.enabled",
        "canonical-log.okhttp.enabled",
        "canonical-log.scheduling.enabled",
    )

    describe("shipped configuration metadata") {
        it("describes every canonical-log property, with valid JSON and a type + description each") {
            val canonicalProps = mutableMapOf<String, JsonNode>()

            val resources = javaClass.classLoader.getResources("META-INF/spring-configuration-metadata.json")
            for (url in resources) {
                // Each file must parse — a stray comma here disables autocomplete for its starter.
                val root = url.openStream().use { mapper.readTree(it) }
                root.path("properties").forEach { prop ->
                    val name = prop.path("name").asText()
                    if (name.startsWith("canonical-log.")) {
                        canonicalProps[name] = prop
                    }
                }
            }

            canonicalProps.keys shouldContainAll expectedKeys

            expectedKeys.forEach { key ->
                val prop = canonicalProps.getValue(key)
                prop.path("type").asText().isNotBlank() shouldBe true
                prop.path("description").asText().isNotBlank() shouldBe true
            }
        }
    }
})
