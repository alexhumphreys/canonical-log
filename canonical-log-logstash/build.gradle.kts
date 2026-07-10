dependencies {
    api(project(":canonical-log-core"))
    implementation(libs.slf4j.api)
    // Encoder types (StructuredArguments) never appear in public signatures.
    implementation(libs.logstash.logback.encoder)

    // ListAppender round-trip test needs a real logback backend.
    testImplementation(libs.logback.classic)
    // canonicalFields(event) flattener, reused for the concurrent-emit integrity test (todo 039).
    testImplementation(project(":canonical-log-test"))
}
