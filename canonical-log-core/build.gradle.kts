plugins {
    alias(libs.plugins.pitest)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    // For the Java ergonomics smoke test (src/test/java) — Kotest isn't callable from Java.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.property)
    testImplementation(libs.logback.classic)
    // Test-scope only JSON parser: the oracle for canonicalLineJson's round-trip property test.
    // Core's main classpath stays dependency-free (slf4j-api + coroutines).
    testImplementation(libs.jackson.databind)
    // Lincheck model-checks CanonicalLogContext's linearizability (CanonicalLogContextLincheckTest).
    // Runs under plain JUnit — the second deliberate exception to the Kotest-only rule.
    testImplementation(libs.lincheck)
    // DebugProbes leak assertion in the property specs (todo 040) — asserts no coroutine
    // outlives a property iteration. Test-scope only.
    testImplementation(libs.kotlinx.coroutines.debug)
    pitest(libs.pitest.junit5.plugin)
}

// Mutation testing (todo 040): manual + nightly only (`./gradlew :canonical-log-core:pitest`).
// The plugin does not wire `pitest` into `check`/`build`, so the per-PR pipeline is unaffected
// by its runtime. Scoped to core's own package; starters/adapters are thin wiring and poor
// mutation targets. See docs/CLAUDE.md's mutation-testing gotcha for the accepted-survivor policy.
pitest {
    targetClasses.set(listOf("io.github.alexhumphreys.canonicallog.*"))
    junit5PluginVersion.set(libs.versions.pitest.junit5.get())
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
}

// Lincheck's model-checking strategy instruments JDK internals; these opens/exports plus
// self-attach are its documented requirement on modern JDKs.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.action=ALL-UNNAMED",
        "-Djdk.attach.allowAttachSelf=true",
    )
    // Forward the deep-exploration switch (a nightly CI job can flip it; defaults stay modest).
    systemProperty("lincheck.deep", System.getProperty("lincheck.deep") ?: "false")
}
