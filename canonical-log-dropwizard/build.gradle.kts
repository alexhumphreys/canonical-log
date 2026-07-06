dependencies {
    // Brings the servlet lifecycle (CanonicalLogServletFilter, HttpWorkUnitAdapter,
    // HttpExchange, PathExclusions) and, transitively, core (MdcCanonicalLineWriter,
    // the writer/sampler/adapter seams).
    api(project(":canonical-log-servlet"))

    // Dropwizard is compileOnly so the adopter's own Dropwizard version wins at runtime —
    // this module built against 4.0.x (jakarta namespace); Dropwizard 2/3 (javax.*) is
    // not supported. dropwizard-core transitively brings dropwizard-jersey → jersey-server,
    // which is where ExtendedUriInfo (the route-template source) lives, so no explicit
    // jersey-server dep is needed on the compile classpath.
    compileOnly(libs.dropwizard.core)
    // The lifecycle/route-capture code WARN-logs telemetry failures directly.
    implementation(libs.slf4j.api)

    // Tests spin up a real in-process Jetty via DropwizardTestSupport (dropwizard-testing
    // pulls dropwizard-core onto the test classpath) and need a real logback backend for
    // the ListAppender round-trip.
    testImplementation(libs.dropwizard.testing)
    testImplementation(libs.logback.classic)
}
