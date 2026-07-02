dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    // For the Java ergonomics smoke test (src/test/java) — Kotest isn't callable from Java.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.property)
    testImplementation(libs.logback.classic)
}
