plugins {
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    api(project(":canonical-log-core"))
    implementation(libs.spring.boot.autoconfigure)
    // ScheduledTaskObservationContext lives in spring-context; ObservationHandler/Registry in
    // micrometer-observation. Both arrive transitively via autoconfigure, but declare the ones
    // we compile against explicitly (versions managed by the BOM).
    implementation("org.springframework:spring-context")
    implementation("io.micrometer:micrometer-observation")
    // Default emit sink logs to the "canonical" logger as logstash structured arguments,
    // matching the HTTP starter's LogstashCanonicalLineWriter.
    implementation(libs.slf4j.api)
    implementation(libs.logstash.logback.encoder)

    testImplementation(project(":canonical-log-core"))
    testImplementation(libs.spring.boot.starter)
    testImplementation(libs.spring.boot.starter.test)
    // logback (for the ListAppender capture) comes from spring-boot-starter-logging at the
    // BOM-managed version — pinning it here skews logback-core vs -classic and breaks boot.
}
