plugins {
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    api(project(":canonlog-core"))
    api(project(":canonlog-okhttp-spring-boot-starter"))
    api(project(":canonlog-jdbc-spring-boot-starter"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.property)
}
