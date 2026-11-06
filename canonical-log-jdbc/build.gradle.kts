dependencies {
    implementation(project(":canonical-log-core"))
    implementation(libs.datasource.proxy)

    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
    testImplementation(project(":canonical-log-test"))
    // In-memory H2 for the contributor contract: the real proxied DataSource, no container.
    testImplementation(libs.h2)
}
