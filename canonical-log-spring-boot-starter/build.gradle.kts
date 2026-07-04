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
    api(project(":canonical-log-logstash"))
    // The HTTP work-unit lifecycle now lives in canonical-log-servlet (todo 021); the starter's
    // filter is a thin Spring-flavoured caller. `api` so adopters' WorkUnitAdapter<HttpExchange>
    // beans see the (re-homed) HttpExchange/HttpWorkUnitAdapter types.
    api(project(":canonical-log-servlet"))
    api(project(":canonical-log-okhttp-spring-boot-starter"))
    api(project(":canonical-log-jdbc-spring-boot-starter"))
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.property)
    // Filter tests read canonical fields back off logstash markers (the default writer's output).
    // The encoder is only a transitive `implementation` dep via canonical-log-logstash, so it
    // isn't on the test compile classpath unless declared here.
    testImplementation(libs.logstash.logback.encoder)
}
