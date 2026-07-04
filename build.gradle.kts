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
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
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
