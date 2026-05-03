plugins {
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    api(project(":canonlog-okhttp"))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.okhttp)

    testImplementation(project(":canonlog-core"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.okhttp.mockwebserver)
}
