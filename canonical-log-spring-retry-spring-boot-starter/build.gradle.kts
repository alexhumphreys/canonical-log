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

    // Classic spring-retry is a compileOnly floor — only its RetryListener SPI and RetryContext
    // are touched. The Spring Framework 7 built-in @Retryable path needs no dependency beyond
    // spring-context (it listens to a MethodRetryEvent), so an app on Boot 4 gets that half
    // whether or not classic spring-retry is present.
    compileOnly(libs.spring.retry)

    testImplementation(libs.spring.retry)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.aspectjweaver)
}
