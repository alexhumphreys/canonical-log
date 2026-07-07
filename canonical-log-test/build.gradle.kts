dependencies {
    api(project(":canonical-log-core"))
    implementation(libs.kotlinx.coroutines.core)

    // RecordingCanonicalAppender observes real booted stacks through a logback ListAppender,
    // and ILoggingEvent appears in its public API — so logback-classic is an `api` main dep.
    // logback is already the sink every adopter test uses; this is deliberately the only extra
    // main dependency, and it is NOT kotest/JUnit/kotlinx (the module's main-source-set rule).
    api(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
}
