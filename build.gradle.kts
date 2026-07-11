import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
    alias(libs.plugins.axion.release)
    alias(libs.plugins.pitest) apply false
}

// Version is derived from git tags by axion-release. On a tagged commit this is a
// clean release version (e.g. 1.2.0); otherwise the next version with a -SNAPSHOT
// suffix. The release job bumps the tag; the bump size (major/minor/patch) is chosen
// from the merged PR's conventional-commit title via -Prelease.versionIncrementer.
scmVersion {
    tag {
        prefix.set("v")
    }
    versionIncrementer("incrementPatch")
}

val computedVersion = scmVersion.version

subprojects {
    group = "io.github.alexhumphreys"
    version = computedVersion

    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    dependencies {
        "testImplementation"(rootProject.libs.kotest.runner.junit5)
        "testImplementation"(rootProject.libs.kotest.assertions.core)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Nightly CI matrix (todo 040): -XX:ActiveProcessorCount forces the JVM to believe it
    // has N cores, so ForkJoin/coroutine-IO pools size themselves accordingly and
    // preemption-driven interleavings that only show up under real contention (=1) or heavy
    // parallelism get exercised. Plumbed the same way as the existing -Ptest.jdk=17 launcher
    // override. Left unset in the default per-PR run.
    providers.gradleProperty("test.cpus").orNull?.let { cpus ->
        tasks.withType<Test>().configureEach {
            jvmArgs("-XX:ActiveProcessorCount=$cpus")
        }
    }

    val isLibrary = path.startsWith(":canonical-log-")
    if (isLibrary) {
        apply(plugin = "maven-publish")

        // Library modules target Java 17 bytecode so 17-floor consumers (Dropwizard 4,
        // pinned platform toolchains) can resolve and load them, while compile + tests
        // still run on the JDK 25 toolchain. -Xjdk-release / options.release are the
        // load-bearing flags: they compile against the 17 API surface (ct.sym), so a
        // reference to an 18+-only method fails at compile time rather than shipping a jar
        // that NoSuchMethodErrors on a real 17 JVM. Raising this floor later is a breaking
        // change for consumers — see docs/CLAUDE.md decisions. (todo 025)
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        // Test sources get the JVM_17 bytecode target (so class files still load under the
        // test-jdk17 launcher below) but NOT `-Xjdk-release`/`options.release`: that flag
        // restricts the *API surface* to ct.sym 17, which would make JDK21+-only test code
        // (e.g. VirtualThreadTortureTest's virtual-thread APIs, gated to skip at runtime on
        // JDK <21) fail to *compile* rather than just skip at test time. Main sources keep
        // the full release=17 restriction — that's the actual shipped-jar safety net.
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
        tasks.named<JavaCompile>("compileTestJava").configure {
            options.release.set(null)
        }
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        tasks.named<KotlinCompile>("compileKotlin").configure {
            compilerOptions {
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }

        tasks.named<KotlinCompile>("compileKotlin").configure {
            compilerOptions {
                freeCompilerArgs.add("-Xexplicit-api=strict")
            }
        }

        // Prove the 17-target jars actually load and run on a real 17 JVM: with
        // -Ptest.jdk=17, run the library test suites on a JDK 17 launcher (compilation
        // still uses the 25 toolchain). Scoped to library modules — samples target 21 and
        // can't load on 17. CI exercises this in a dedicated job. (todo 025)
        providers.gradleProperty("test.jdk").orNull?.let { testJdk ->
            val toolchains = extensions.getByType<JavaToolchainService>()
            tasks.withType<Test>().configureEach {
                javaLauncher.set(
                    toolchains.launcherFor {
                        languageVersion.set(JavaLanguageVersion.of(testJdk))
                    },
                )
            }
        }

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    afterEvaluate { from(components["java"]) }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/alexhumphreys/canonical-log")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}

val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = the()
