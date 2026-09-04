plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(project(":canonical-log-spring-boot-starter"))
    implementation(project(":canonical-log-scheduling-spring-boot-starter"))
    implementation(project(":canonical-log-resilience4j-spring-boot-starter"))
    implementation(libs.resilience4j.all)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.okhttp)
    implementation(libs.okhttp.mockwebserver.runtime)
    implementation(libs.h2)
    implementation(libs.logstash.logback.encoder)

    // Jackson 2, for the test-side JSON assertions. Declared explicitly because it arrives
    // transitively through logstash-logback-encoder today, and the encoder moves to
    // Jackson 3 (tools.jackson) in 9.0 — a logging encoder was never the right source for
    // a test's JSON parser. Matches how canonical-log-core declares it.
    testImplementation(libs.jackson.databind)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.testcontainers.junit.jupiter)
}
