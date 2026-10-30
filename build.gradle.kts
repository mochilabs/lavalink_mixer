import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    `java-library`
    `maven-publish`
}

group = "dev.lavalink.mixer"

// Version follows the release tag. GitHub Releases pass it via `-Pversion=`
// (release.yml); local/dev builds without a tag fall back to a snapshot so
// the jar is never mislabeled with a stale version. A leading `v` is stripped
// so `v1.2.3` produces `lavalink_mixer-1.2.3.jar`.
val taggedVersion: String? = (
    providers.environmentVariable("MIXER_VERSION").orNull
        ?: (project.findProperty("version") as? String)?.takeIf { it != "unspecified" }
    )?.removePrefix("v")?.takeIf { it.isNotEmpty() }
version = taggedVersion
    ?: runCatching {
        val git = providers.exec { commandLine("git", "describe", "--tags", "--exact-match") }.standardOutput.asText.get().trim()
        git.removePrefix("v").takeIf { it.isNotEmpty() }
    }.getOrNull()
    ?: "0.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
}

dependencies {
    // Provided by the Lavalink server at runtime (transitively includes
    // spring-context/spring-web for @Service/@RestController and
    // kotlinx-serialization-json for filter config parsing).
    compileOnly("dev.arbjerg.lavalink:plugin-api:4.2.2")
    // Must match the Lavaplayer version the server runs.
    compileOnly("dev.arbjerg:lavaplayer:2.2.6")
    // compileOnly is not inherited by tests in Gradle; tests also execute code
    // touching these APIs, so they need runtime presence too.
    testImplementation("dev.arbjerg.lavalink:plugin-api:4.2.2")
    testImplementation("dev.arbjerg:lavaplayer:2.2.6")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.20")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks {
    named<Test>("test") {
        useJUnitPlatform()
    }

    processResources {
        filter(ReplaceTokens::class, mapOf("tokens" to mapOf("version" to project.version.toString())))
    }

    // GitHub Packages publish. CI (publish.yml) authenticates with
    // GRADLE_PUBLISH_USER/GRADLE_PUBLISH_KEY; local publishes read the same
    // properties or fall back to a read-only anonymous query.
    publishing {
        publications {
            register<MavenPublication>("plugin") {
                from(project.components.getByName("java"))
                artifactId = "mixer-plugin"
                pom {
                    name.set("mixer-plugin")
                    description.set("Lavalink v4 plugin: gapless, crossfade, TTS ducking, and volume normalization")
                    url.set("https://github.com/mochilabs/lavalink_mixer")
                    licenses {
                        license {
                            name.set("Unlicense")
                            url.set("https://unlicense.org")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/mochilabs/lavalink_mixer")
                credentials {
                    username = (findProperty("gpr.user") as String?) ?: System.getenv("GRADLE_PUBLISH_USER") ?: ""
                    password = (findProperty("gpr.key") as String?) ?: System.getenv("GRADLE_PUBLISH_KEY") ?: ""
                }
            }
        }
    }
}
