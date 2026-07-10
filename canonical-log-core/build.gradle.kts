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
