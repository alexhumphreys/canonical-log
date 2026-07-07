dependencies {
    api(project(":canonical-log-core"))

    // Kafka clients is a compileOnly floor: the adapter/decorator only touch ConsumerRecord,
    // Producer, Callback and RecordMetadata (stable across 2.x/3.x), so the adopter's own Kafka
    // client version wins at runtime. Core never sees Kafka.
    compileOnly(libs.kafka.clients)

    // Tests need the real record types plus MockProducer to drive callbacks without a broker.
    testImplementation(libs.kafka.clients)
    testImplementation(project(":canonical-log-test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
