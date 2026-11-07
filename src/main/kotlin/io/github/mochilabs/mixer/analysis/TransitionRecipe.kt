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
}

enum class FilterType {
    HIGH_PASS,
}

enum class TransitionStrategy {
    PHRASE_MATCHED_BASS_SWAP,
    SHORT_BASS_SWAP,
    BEATMATCHED_BLEND,
    ECHO_OUT,
    DOWNBEAT_CUT,
    DEGRADED_FILTER_SWEEP,
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
    val volumeRamps: List<ParamAutomation.VolumeRamp> = automations.filterIsInstance<ParamAutomation.VolumeRamp>()
    val bassCuts: List<ParamAutomation.BassCut> = automations.filterIsInstance<ParamAutomation.BassCut>()
    val filterSweeps: List<ParamAutomation.FilterCutoff> = automations.filterIsInstance<ParamAutomation.FilterCutoff>()
    val echoTrigger: ParamAutomation.EchoTrigger? = automations.filterIsInstance<ParamAutomation.EchoTrigger>().firstOrNull()
    val hasBassCuts: Boolean get() = bassCuts.isNotEmpty()

    fun gainAt(target: TrackTarget, currentSample: Long, defaultGain: Float): Float {
        var activeRamp: ParamAutomation.VolumeRamp? = null
        var pastRamp: ParamAutomation.VolumeRamp? = null
        for (i in volumeRamps.indices) {
            val r = volumeRamps[i]
            if (r.target == target) {
                if (currentSample in r.startSample..r.endSample) {
                    activeRamp = r
                } else if (currentSample > r.endSample) {
                    pastRamp = r
                }
            }
        }
        if (activeRamp != null) {
            val span = (activeRamp.endSample - activeRamp.startSample).coerceAtLeast(1L)
            val progress = ((currentSample - activeRamp.startSample).toDouble() / span.toDouble()).coerceIn(0.0, 1.0).toFloat()
            return when (activeRamp.curve) {
                RampCurve.LINEAR -> activeRamp.fromGain + (activeRamp.toGain - activeRamp.fromGain) * progress
                RampCurve.EQUAL_POWER -> {
                    if (activeRamp.fromGain > activeRamp.toGain) {
                        cos(progress * Math.PI.toFloat() * 0.5f) * activeRamp.fromGain
                    } else {
                        sin(progress * Math.PI.toFloat() * 0.5f) * activeRamp.toGain
                    }
                }
            }
        }
        return pastRamp?.toGain ?: defaultGain
    }

    fun bassGainAt(target: TrackTarget, currentSample: Long): Float {
        var activeCut: ParamAutomation.BassCut? = null
        for (i in bassCuts.indices) {
            val c = bassCuts[i]
            if (c.target == target && currentSample >= c.atSample) {
                if (activeCut == null || c.atSample >= activeCut.atSample) {
                    activeCut = c
                }
            }
        }
        val cut = activeCut ?: return 1.0f
        val elapsed = currentSample - cut.atSample
        val ramp = cut.rampSamples.coerceAtLeast(1)
        if (elapsed >= ramp) {
            return if (cut.muteLow) 0.0f else 1.0f
        }
        val progress = (elapsed.toDouble() / ramp.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return if (cut.muteLow) {
            cos(progress * Math.PI.toFloat() * 0.5f)
        } else {
            sin(progress * Math.PI.toFloat() * 0.5f)
        }
    }

    fun isBassMutedAt(target: TrackTarget, currentSample: Long): Boolean {
        return bassGainAt(target, currentSample) < 0.5f
    }

    fun filterCutoffAt(target: TrackTarget, currentSample: Long): Float? {
        var activeSweep: ParamAutomation.FilterCutoff? = null
        for (i in filterSweeps.indices) {
            val s = filterSweeps[i]
            if (s.target == target && currentSample in s.startSample..s.endSample) {
                activeSweep = s
            }
        }
        val sweep = activeSweep ?: return null
        val span = (sweep.endSample - sweep.startSample).coerceAtLeast(1L)
        val progress = ((currentSample - sweep.startSample).toDouble() / span.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return sweep.startHz + (sweep.endHz - sweep.startHz) * progress
    }

    fun echoTerminationGainAt(currentSample: Long): Float {
        val trigger = echoTrigger ?: return 0f
        if (currentSample < trigger.atSample) return 0f
        val elapsed = currentSample - trigger.atSample
        val termDuration = trigger.terminationRampSamples.coerceAtLeast(1)
        return (1.0f - (elapsed.toFloat() / termDuration)).coerceIn(0f, 1f)
    }
}
