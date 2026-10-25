package dev.lavalink.mixer.plugin

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Per-track gain computed from streaming loudness statistics.
 *
 * Loudness is tracked as a running RMS over square-sums; the linear gain
 * pulls a track's measured RMS to [targetRms] with a ceiling so quiet
 * material is never boosted into hiss. Gains are cached by track id so a
 * re-queued track skips the analysis pass entirely.
 */
data class NormalizationGain(
    val gain: Float,
    val measuredRms: Float,
) {
    companion object {
        const val DEFAULT_TARGET_RMS: Float = 0.1f
        const val MIN_GAIN: Float = 0.25f
        const val MAX_GAIN: Float = 4.0f
        const val MAX_BOOST_DB: Float = 12f

        fun fromRms(measuredRms: Float, targetRms: Float = DEFAULT_TARGET_RMS): NormalizationGain {
            // A track whose whole analysis window was silent gets no boost.
            if (measuredRms <= 1e-9f) return NormalizationGain(1f, measuredRms)
            val target = targetRms.coerceAtLeast(1e-9f)
            val gain = (target / measuredRms).coerceIn(MIN_GAIN, MAX_GAIN)
            return NormalizationGain(gain, measuredRms)
        }
    }
}

/**
 * Streaming loudness accumulator: feed decoded chunks as they arrive; the
 * RMS is exact once [finish] is called. [samples] counts every sample across
 * all channels, so the RMS covers the full waveform.
 */
class LoudnessMeter {
    private var squareSum: Double = 0.0
    private var samples: Long = 0L

    fun feed(frames: Array<FloatArray>, offset: Int, length: Int) {
        for (channel in frames) {
            for (i in offset until offset + length) {
                val s = channel[i].toDouble()
                squareSum += s * s
                samples++
            }
        }
    }

    fun feed(frames: List<Array<FloatArray>>) {
        for (frame in frames) feed(frame, 0, frame[0].size)
    }

    fun finish(): Float {
        if (samples == 0L) return 0f
        return sqrt(squareSum / samples).toFloat()
    }
}
