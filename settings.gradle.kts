rootProject.name = "canonical-log"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "canonical-log-core",
    "canonical-log-okhttp",
    "canonical-log-okhttp-spring-boot-starter",
    "canonical-log-jdbc",
    "canonical-log-jdbc-spring-boot-starter",
    "canonical-log-scheduling-spring-boot-starter",
    "canonical-log-spring-boot-starter",
    "canonical-log-test",
    "samples:spring-demo",
)
