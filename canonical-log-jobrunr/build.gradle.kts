dependencies {
    api(project(":canonical-log-core"))

    // JobRunr is a compileOnly floor: CanonicalJobServerFilter only touches the open-source
    // JobServerFilter callbacks and the Job model (id / jobName / state history), so the adopter's
    // own JobRunr — OSS or Pro — wins at runtime. Core never sees JobRunr, mirroring
    // canonical-log-sqs / canonical-log-tracing-otel.
    compileOnly(libs.jobrunr)

    // The e2e boots a real BackgroundJobServer + InMemoryStorageProvider (both ship in the main
    // jobrunr artifact) and observes the emitted line off the worker thread with
    // RecordingCanonicalAppender. jackson-databind is the JSON mapper JobRunr auto-detects; the
    // logstash writer (+ its encoder, brought transitively) gives type-preserving canonical lines.
    testImplementation(libs.jobrunr)
    testImplementation(libs.jackson.databind)
    testImplementation(project(":canonical-log-test"))
    testImplementation(project(":canonical-log-logstash"))
    testImplementation(libs.logback.classic)
}
