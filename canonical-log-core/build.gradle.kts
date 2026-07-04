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
}
