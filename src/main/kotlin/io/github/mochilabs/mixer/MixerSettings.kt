package io.github.mochilabs.mixer

/**
 * A guild's runtime-adjustable settings, seeded from [MixerConfig].
 *
 * Every field is volatile so the voice and analysis threads can read it without
 * the guild lock. [GuildMixer] exposes each one as a property of the same name.
 */
internal class MixerSettings(config: MixerConfig) {
    @Volatile var crossfadeEnabled: Boolean = config.crossfadeEnabled
    @Volatile var crossfadeMs: Long = config.crossfadeMs
    @Volatile var fadeInMs: Long = config.fadeInMs
    @Volatile var duckLevel: Float = config.duckLevel
    @Volatile var duckFadeMs: Long = config.duckFadeMs
    @Volatile var maskMs: Long = config.maskMs
    @Volatile var handoffBridgeMs: Long = config.handoffBridgeMs
    @Volatile var maxParallelOverlays: Int = config.maxParallelOverlays.coerceIn(1, 16)
    @Volatile var normalizationEnabled: Boolean = config.normalizationEnabled
    @Volatile var targetLufs: Float = config.targetLufs
    @Volatile var silenceSkipEnabled: Boolean = config.silenceSkipEnabled
    @Volatile var silenceThresholdDb: Float = config.silenceThresholdDb
    @Volatile var silenceMinSoundMs: Long = config.silenceMinSoundMs
    @Volatile var silenceHeadScanMs: Long = config.silenceHeadScanMs
    @Volatile var silenceTailScanMs: Long = config.silenceTailScanMs
    @Volatile var tailConfirmMs: Long = config.tailConfirmMs
    @Volatile var bpmDetectionEnabled: Boolean = config.bpmDetectionEnabled
    @Volatile var bpmScanMs: Long = config.bpmScanMs.coerceIn(5000, 60000)
    @Volatile var keyDetectionEnabled: Boolean = config.keyDetectionEnabled
    @Volatile var keyScanMs: Long = config.keyScanMs.coerceIn(5000, 60000)
    @Volatile var beatmatchEnabled: Boolean = config.beatmatchEnabled
    @Volatile var maxTempoChange: Float = config.maxTempoChange.coerceIn(0f, 0.12f)
    @Volatile var beatmatchMinConfidence: Float = config.beatmatchMinConfidence.coerceIn(0f, 1f)
}
