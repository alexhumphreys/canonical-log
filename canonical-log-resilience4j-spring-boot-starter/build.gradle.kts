plugins {
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    api(project(":canonical-log-resilience4j"))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.resilience4j.all)

    testImplementation(project(":canonical-log-core"))
    testImplementation(libs.resilience4j.all)
    testImplementation(libs.spring.boot.starter.test)
}
