dependencies {
    api(project(":canonical-log-core"))

    // AWS SDK SQS is a compileOnly floor: SqsMessageWorkUnitAdapter only touches the Message
    // model type and MessageSystemAttributeName, so the adopter's own AWS SDK version wins at
    // runtime. Core never sees the SDK — framework-agnostic, mirroring canonical-log-tracing-otel.
    compileOnly(libs.awssdk.sqs)

    // Tests build a Message via Message.builder() — pure data, no LocalStack/containers — and
    // drive it through the real work-unit lifecycle with the capture harness.
    testImplementation(libs.awssdk.sqs)
    testImplementation(project(":canonical-log-test"))
}
