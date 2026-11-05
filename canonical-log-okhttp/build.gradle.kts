dependencies {
    implementation(project(":canonical-log-core"))
    implementation(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(project(":canonical-log-test"))
}
