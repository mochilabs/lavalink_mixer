package io.github.mochilabs.mixer

import kotlin.math.cos
import kotlin.math.sin

enum class TrackTarget {
    OUTGOING,
    INCOMING,
}

enum class RampCurve {
    LINEAR,
    EQUAL_POWER,
    EXPONENTIAL,
}

enum class FilterType {
    HIGH_PASS,
    LOW_PASS,
    BAND_PASS,
}

enum class TransitionStrategy {
    PHRASE_MATCHED_BASS_SWAP,
    SHORT_BASS_SWAP,
    BEATMATCHED_BLEND,
    ECHO_OUT,
    DOWNBEAT_CUT,
    DEGRADED_FILTER_SWEEP,
    SILENCE_TRIM,
    ABSOLUTE_FALLBACK,
}

sealed interface ParamAutomation {
    data class VolumeRamp(
        val target: TrackTarget,
        val startSample: Long,
        val endSample: Long,
        val fromGain: Float,
        val toGain: Float,
        val curve: RampCurve = RampCurve.EQUAL_POWER,
    ) : ParamAutomation

    data class FilterCutoff(
        val target: TrackTarget,
        val type: FilterType,
        val startSample: Long,
        val endSample: Long,
        val startHz: Float,
        val endHz: Float,
    ) : ParamAutomation

    data class BassCut(
        val target: TrackTarget,
        val atSample: Long,
        val muteLow: Boolean,
        val rampSamples: Int = 720, // 15ms @ 48kHz
    ) : ParamAutomation

    data class EchoTrigger(
        val atSample: Long,
        val delaySamples: Int,
        val feedback: Float = 0.65f,
        val terminationRampSamples: Int = 86400, // 1.8s @ 48kHz
    ) : ParamAutomation
}

data class BeatSyncConfig(
    val enabled: Boolean,
    val targetBpm: Float,
    val rateRatio: Float = 1.0f,
    val startSample: Long = 0L,
    val handoffSample: Long = 0L,
    val incomingStartSample: Long = 0L,
    val recoverySamples: Long = 0L,
)

data class TransitionRecipe(
    val strategy: TransitionStrategy,
    val totalSamples: Long,
    val sync: BeatSyncConfig,
    val automations: List<ParamAutomation> = emptyList(),
    val reason: String = strategy.name.lowercase(),
) {
    fun gainAt(target: TrackTarget, currentSample: Long, defaultGain: Float): Float {
        val ramp = automations.filterIsInstance<ParamAutomation.VolumeRamp>()
            .lastOrNull { it.target == target && currentSample in it.startSample..it.endSample }
        if (ramp != null) {
            val progress = ((currentSample - ramp.startSample).toDouble() / (ramp.endSample - ramp.startSample).coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat()
            return when (ramp.curve) {
                RampCurve.LINEAR -> ramp.fromGain + (ramp.toGain - ramp.fromGain) * progress
                RampCurve.EQUAL_POWER -> {
                    if (ramp.fromGain > ramp.toGain) {
                        cos(progress * Math.PI.toFloat() * 0.5f) * ramp.fromGain
                    } else {
                        sin(progress * Math.PI.toFloat() * 0.5f) * ramp.toGain
                    }
                }
                RampCurve.EXPONENTIAL -> {
                    val p = progress * progress
                    ramp.fromGain + (ramp.toGain - ramp.fromGain) * p
                }
            }
        }
        val pastRamp = automations.filterIsInstance<ParamAutomation.VolumeRamp>()
            .lastOrNull { it.target == target && currentSample > it.endSample }
        return pastRamp?.toGain ?: defaultGain
    }

    fun isBassMutedAt(target: TrackTarget, currentSample: Long): Boolean {
        val lastCut = automations.filterIsInstance<ParamAutomation.BassCut>()
            .filter { it.target == target && currentSample >= it.atSample }
            .maxByOrNull { it.atSample }
        return lastCut?.muteLow ?: false
    }

    fun filterCutoffAt(target: TrackTarget, currentSample: Long): Float? {
        val sweep = automations.filterIsInstance<ParamAutomation.FilterCutoff>()
            .lastOrNull { it.target == target && currentSample in it.startSample..it.endSample }
            ?: return null
        val progress = ((currentSample - sweep.startSample).toDouble() / (sweep.endSample - sweep.startSample).coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat()
        return sweep.startHz + (sweep.endHz - sweep.startHz) * progress
    }
}
