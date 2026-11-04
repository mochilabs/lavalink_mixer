package io.github.mochilabs.mixer

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Defaults (overridable per guild via REST). Also bindable from application.yml under `mixer:`. */
@Component
@ConfigurationProperties(prefix = "mixer")
class MixerConfig {
    var crossfadeEnabled: Boolean = true
    var crossfadeMs: Long = 5000
    var fadeInMs: Long = 300
    var duckLevel: Float = 0.2f
    var duckFadeMs: Long = 300
    var maskMs: Long = 80

    // Volume normalization is opt-in: every preloaded track is decoded once
    // more for loudness analysis, which costs CPU/bandwidth.
    var normalizationEnabled: Boolean = false
    /** Target loudness in LUFS for EBU R128 normalization (default -14.0 LUFS, YouTube/Spotify standard). */
    var targetLufs: Float = -14.0f

    // Silence skipping is opt-in: every preloaded track is decoded once more
    // for analysis, which costs CPU/bandwidth.
    var silenceSkipEnabled: Boolean = false
    var silenceThresholdDb: Float = -60f
    var silenceMinSoundMs: Long = 120
    var silenceHeadScanMs: Long = 15000
    var silenceTailScanMs: Long = 15000
    /** Sustained realtime silence before an early cut (streams demand max(this, 3000)). */
    var tailConfirmMs: Long = 1000

    // Tempo (BPM) detection is opt-in: each preloaded track is decoded once
    // more for onset analysis, which costs CPU/bandwidth.
    var bpmDetectionEnabled: Boolean = false
    /** Head window (ms) decoded for tempo analysis; 30s spans ~15-60 beats across 60-200 BPM. */
    var bpmScanMs: Long = 30000

    /** Independent, opt-in major/minor key estimation from a bounded head window. */
    var keyDetectionEnabled: Boolean = false
    var keyScanMs: Long = 30000

    // Beat-matched crossfading (aligns beat grids of outgoing and incoming tracks)
    var beatmatchEnabled: Boolean = false
    var maxTempoChange: Float = 0.06f
    var beatmatchMinConfidence: Float = 0.30f

    /** Parallel silence analyses (per-guild decoders are independent). Read at startup. */
    var analysisThreads: Int = 2

    /** Upper bound on simultaneous parallel (`mode=parallel`) TTS overlays per guild. */
    var maxParallelOverlays: Int = 4
}
