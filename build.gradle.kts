import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
        tasks.named<KotlinCompile>("compileKotlin").configure {
            compilerOptions {
                freeCompilerArgs.add("-Xexplicit-api=strict")
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
