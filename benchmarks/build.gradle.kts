// JMH benchmarks for the canonical-log hot path (todo 044).
//
// Manual + nightly only — nothing here is wired into `check`/`build`, so the per-PR
// pipeline is unaffected by the runtime (a full run is minutes, not seconds). The
// per-PR allocation gate lives in canonical-log-core's AllocationBudgetTest, which
// measures the same operations with `getThreadAllocatedBytes` in about a second; this
// module supplies the *throughput* numbers that test can't measure, and `-prof gc`
// gives an independent second opinion on the byte figures.
//
//   ./gradlew :benchmarks:jmh                                  # everything
//   ./gradlew :benchmarks:jmh -Pjmh.args='Put -prof gc'        # one benchmark + alloc profile
//
// Not published (the module is deliberately not named `canonical-log-*`, so the root
// build's `isLibrary` branch doesn't apply maven-publish or the Java 17 target to it).
plugins {
    java
}

dependencies {
    implementation(project(":canonical-log-core"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator.annprocess)
    // Real slf4j binding: the emit path logs, and a no-op binding would benchmark a
    // different code path than the one adopters run.
    runtimeOnly(libs.logback.classic)
}

// Plain JavaExec rather than the me.champeau.jmh plugin: JMH's own Main is the whole
// integration, and this keeps the build free of another third-party Gradle plugin.
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Run the JMH benchmarks. Pass -Pjmh.args='<jmh cli args>' to filter or profile."
    dependsOn(tasks.named("classes"))
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["main"].runtimeClasspath
    args(
        (providers.gradleProperty("jmh.args").orNull ?: "")
            .split(" ")
            .filter { it.isNotBlank() },
    )
}
