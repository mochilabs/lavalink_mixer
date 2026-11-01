package dev.lavalink.mixer.plugin

import java.util.Properties

/**
 * Resolves the plugin version at runtime from the packaged plugin descriptor
 * (`lavalink-plugins/mixer-plugin.properties`). Gradle's `processResources`
 * replaces the descriptor's `@version@` token with the build version, so the
 * value matches the JAR that is actually running. Falls back to [FALLBACK]
 * when the resource is absent or un-replaced (e.g. an unusual test classpath).
 */
object MixerVersion {
    /** Wire protocol version of this plugin's control plane. */
    const val PROTOCOL_VERSION = 2

    private const val FALLBACK = "0.0.0-SNAPSHOT"
    private const val DESCRIPTOR = "lavalink-plugins/mixer-plugin.properties"

    val version: String by lazy {
        runCatching {
            MixerVersion::class.java.classLoader
                .getResourceAsStream(DESCRIPTOR)
                ?.use { stream ->
                    Properties().apply { load(stream) }.getProperty("version")
                }
                ?.takeIf { it.isNotBlank() && !it.startsWith("@") }
        }.getOrNull() ?: FALLBACK
    }
}
