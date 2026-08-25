dependencies {
    api(project(":canonical-log-core"))

    // Resilience4j is a compileOnly floor: CanonicalResilience4j only touches the registries'
    // EventPublisher surface and the event types (stable across 2.x), so the adopter's own
    // Resilience4j version wins at runtime. Core never sees Resilience4j, mirroring
    // canonical-log-kafka / canonical-log-jobrunr.
    compileOnly(libs.resilience4j.all)

    // Tests drive real registries in-process — no mocking of the event publishers.
    testImplementation(libs.resilience4j.all)
    testImplementation(project(":canonical-log-test"))
}
