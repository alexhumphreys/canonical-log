dependencies {
    api(project(":canonical-log-core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
}
