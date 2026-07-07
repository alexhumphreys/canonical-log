dependencies {
    api(project(":canonical-log-core"))

    // OTel API is a compileOnly floor: OtelSeedingAdapter only touches Span.current() and
    // SpanContext (stable since OTel 1.0), so the adopter's own OpenTelemetry version wins at
    // runtime. Core never sees OTel — the anti-goal keeps it a dependency-free module.
    compileOnly(libs.opentelemetry.api)

    // Tests wire a real SdkTracerProvider (sdk-testing) and drive spans through the adapter.
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk.testing)
    // The async pin runs a suspend work unit that switches dispatchers.
    testImplementation(project(":canonical-log-test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
