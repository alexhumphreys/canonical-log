dependencies {
    api(project(":canonical-log-core"))
    // The lifecycle helper WARN-logs telemetry failures directly (core keeps slf4j as an
    // `implementation` dep, so it isn't transitive).
    implementation(libs.slf4j.api)
    // Servlet API is a compileOnly floor (5.0.0 — see the catalog note): the module compiles
    // against the lowest jakarta.* servlet surface so it loads on EE9 and EE10+ containers
    // alike. Whoever registers the filter (a starter, a plain servlet app) brings the runtime
    // servlet-api. Everything used here (AsyncListener/AsyncContext, isAsyncStarted, request
    // attributes, HttpFilter) has existed since the jakarta.* namespace began.
    compileOnly(libs.jakarta.servlet.api)

    // Tests need the servlet-api on the runtime classpath (the fakes implement its interfaces)
    // and a real logback backend for the ListAppender round-trip.
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.logback.classic)
    testImplementation(libs.kotest.property)
}
