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
    // Default sink is LogstashCanonicalLineWriter; a user CanonicalLineWriter bean overrides it
    // (shared with the HTTP starter via string-named auto-config ordering, todo 020).
    api(project(":canonical-log-logstash"))
    implementation(libs.spring.boot.autoconfigure)
    // ScheduledTaskObservationContext lives in spring-context; ObservationHandler/Registry in
    // micrometer-observation. Both arrive transitively via autoconfigure, but declare the ones
    // we compile against explicitly (versions managed by the BOM).
    implementation("org.springframework:spring-context")
    implementation("io.micrometer:micrometer-observation")

    testImplementation(project(":canonical-log-core"))
    testImplementation(libs.spring.boot.starter)
    testImplementation(libs.spring.boot.starter.test)
    // Tests read canonical fields back off logstash markers (the default writer's output). The
    // encoder is only a transitive `implementation` dep via canonical-log-logstash, so it isn't
    // on the test compile classpath unless declared here.
    testImplementation(libs.logstash.logback.encoder)
    // logback (for the ListAppender capture) comes from spring-boot-starter-logging at the
    // BOM-managed version — pinning it here skews logback-core vs -classic and breaks boot.
}
