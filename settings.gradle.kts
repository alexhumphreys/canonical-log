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
    "canonical-log-logstash",
    "canonical-log-servlet",
    "canonical-log-dropwizard",
    "canonical-log-tracing-otel",
    "canonical-log-kafka",
    "canonical-log-sqs",
    "canonical-log-jobrunr",
    "canonical-log-scheduling-spring-boot-starter",
    "canonical-log-spring-boot-starter",
    "canonical-log-test",
    "samples:spring-demo",
)
