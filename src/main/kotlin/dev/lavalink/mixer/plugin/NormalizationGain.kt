package dev.lavalink.mixer.plugin

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Per-track normalization gain computed using absolute EBU R128 / ITU-R BS.1770-4
 * Loudness Units relative to Full Scale (LUFS).
 *
 * Target: [DEFAULT_TARGET_LUFS] (-14.0 LUFS), matching YouTube Music & Spotify web.
 * When integrated loudness is measured, linear gain is:
 *   gain = 10^((targetLufs - measuredLufs) / 20)
 * Attenuation/boost is clamped between [MIN_GAIN] (-12 dB) and [MAX_GAIN] (0 dB / unity).
 * In accordance with conservative mix headroom, we never boost quiet tracks above 1.0 (unity)
 * to prevent inter-sample clipping and distortion during crossfade summing, but quiet tracks
 * can optionally be boosted if [allowBoost] is true (clamped to +3 dB / 1.41f max).
 */
data class NormalizationGain(
    val gain: Float,
    val measuredLufs: Float,
) {
    companion object {
        const val DEFAULT_TARGET_LUFS: Float = -14.0f
        const val MIN_GAIN: Float = 0.25f // -12 dB max attenuation
        const val MAX_GAIN: Float = 1.0f  // 0 dB (no boost into clipping)

        /**
         * Calculates gain from measured integrated LUFS.
         */
        fun fromLufs(
            measuredLufs: Float,
            targetLufs: Float = DEFAULT_TARGET_LUFS,
            allowBoost: Boolean = false,
        ): NormalizationGain {
            // Silence or lower than -70 LUFS (absolute silence gate) -> unity gain
            if (measuredLufs.isNaN() || measuredLufs <= -70.0f) {
                return NormalizationGain(1.0f, measuredLufs)
            }

            val diffDb = targetLufs - measuredLufs
            val linear = 10.0.pow(diffDb / 20.0).toFloat()
            val maxAllowed = if (allowBoost) 1.41f else MAX_GAIN
            val clamped = linear.coerceIn(MIN_GAIN, maxAllowed)
            return NormalizationGain(clamped, measuredLufs)
        }

        /**
         * Backward-compatibility for raw RMS to pseudo-LUFS if needed.
         */
        fun fromRms(measuredRms: Float, targetRms: Float = 0.1f): NormalizationGain {
            if (measuredRms <= 1e-9f) return NormalizationGain(1f, -70f)
            val lufs = (20.0 * log10(measuredRms.toDouble().coerceAtLeast(1e-9)) - 3.01).toFloat()
            val targetLufs = (20.0 * log10(targetRms.toDouble().coerceAtLeast(1e-9)) - 3.01).toFloat()
            return fromLufs(lufs, targetLufs)
        }
    }
}

/**
 * Direct Form II Transposed Biquad Filter.
 * y[n] = b0*x[n] + s1[n-1]
 * s1[n] = b1*x[n] - a1*y[n] + s2[n-1]
 * s2[n] = b2*x[n] - a2*y[n]
 */
class BiquadFilter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var s1: Double = 0.0
    private var s2: Double = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        // Flush-to-zero: prevent JVM denormal CPU spikes when input or filter state decays to sub-normal magnitude.
        if (s1 != 0.0 && kotlin.math.abs(s1) < 1e-15) s1 = 0.0
        if (s2 != 0.0 && kotlin.math.abs(s2) < 1e-15) s2 = 0.0
        return y
    }

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }
}

/**
 * ITU-R BS.1770-4 K-weighting pre-filter for a single audio channel at 48,000 Hz.
 * Stage 1: High-shelf filter (models acoustic head effect, +4 dB boost above ~1.5 kHz)
 * Stage 2: High-pass RLB weighting filter (models human ear curve at low frequencies, cutoff ~38 Hz)
 */
class KWeightingFilter {
    companion object {
        // Stage 1: High-shelf pre-filter (48 kHz)
        private const val PRE_B0 = 1.53512485958697
        private const val PRE_B1 = -2.69169618940638
        private const val PRE_B2 = 1.19839281085285
        private const val PRE_A1 = -1.69065929318241
        private const val PRE_A2 = 0.73248077421585

        // Stage 2: High-pass RLB filter (48 kHz)
        private const val RLB_B0 = 1.0
        private const val RLB_B1 = -2.0
        private const val RLB_B2 = 1.0
        private const val RLB_A1 = -1.99004745483398
        private const val RLB_A2 = 0.99007225036621
    }

    private val stage1 = BiquadFilter(PRE_B0, PRE_B1, PRE_B2, PRE_A1, PRE_A2)
    private val stage2 = BiquadFilter(RLB_B0, RLB_B1, RLB_B2, RLB_A1, RLB_A2)

    fun filter(sample: Float): Float {
        val y1 = stage1.process(sample.toDouble())
        val y2 = stage2.process(y1)
        return y2.toFloat()
    }

    fun reset() {
        stage1.reset()
        stage2.reset()
    }
}

/**
 * ITU-R BS.1770-4 / EBU R128 Loudness Meter.
 * Computes:
 * - Momentary loudness (400ms rectangular window)
 * - Short-term loudness (3s rectangular window)
 * - Integrated loudness with standard two-stage gating (-70 LUFS absolute, -10 LU relative).
 *
 * For stereo audio at 48 kHz (Channel weighting G_L = 1.0, G_R = 1.0).
 */
class LoudnessMeter(
    private val sampleRate: Int = 48000,
) {
    // 400ms block size = 48000 * 0.4 = 19200 samples
    private val blockSamples = (sampleRate * 0.400).toInt()
    // 75% overlap = 100ms hop = 4800 samples
    private val hopSamples = (sampleRate * 0.100).toInt()

    private val filters = Array(2) { KWeightingFilter() }

    // Ring buffer for overlapping 400ms blocks per channel
    private val ringBuffer = Array(2) { DoubleArray(blockSamples) }
    private var ringPos = 0
    private var samplesInRing = 0
    private var hopCounter = 0

    // Stored 400ms momentary block powers: z_j = (sum_channels z_i,j)
    // where z_i,j = 1/N * sum_{k=0}^{N-1} (y_i[k])^2
    private val blockPowers = mutableListOf<Double>()

    fun feed(frames: Array<FloatArray>, offset: Int, length: Int) {
        val channels = minOf(frames.size, 2)
        for (i in offset until offset + length) {
            for (c in 0 until channels) {
                val filtered = filters[c].filter(frames[c][i])
                ringBuffer[c][ringPos] = filtered.toDouble()
            }
            // If mono, mirror to channel 1
            if (channels == 1) {
                ringBuffer[1][ringPos] = ringBuffer[0][ringPos]
            }

            ringPos = (ringPos + 1) % blockSamples
            if (samplesInRing < blockSamples) {
                samplesInRing++
            }

            hopCounter++
            if (samplesInRing == blockSamples && hopCounter >= hopSamples) {
                hopCounter = 0
                recordBlockPower()
            }
        }
    }

    fun feed(frames: List<Array<FloatArray>>) {
        for (f in frames) {
            feed(f, 0, f[0].size)
        }
    }

    private fun recordBlockPower() {
        var totalChannelPower = 0.0
        for (c in 0 until 2) {
            var sumSquare = 0.0
            val buf = ringBuffer[c]
            for (k in 0 until blockSamples) {
                val s = buf[k]
                sumSquare += s * s
            }
            // G_L = 1.0, G_R = 1.0
            totalChannelPower += sumSquare / blockSamples
        }
        blockPowers.add(totalChannelPower)
    }

    /**
     * Completes analysis and computes the EBU R128 integrated loudness (in LUFS/LKFS).
     * If the audio was shorter than a single 400ms block, calculates ungated power from available samples.
     */
    fun finish(): Float {
        // If not enough samples for even one 100ms hop, calculate whatever is in the ring
        if (blockPowers.isEmpty()) {
            if (samplesInRing == 0) return -70.0f
            var totalPower = 0.0
            for (c in 0 until 2) {
                var sumSquare = 0.0
                for (k in 0 until samplesInRing) {
                    val s = ringBuffer[c][k]
                    sumSquare += s * s
                }
                totalPower += sumSquare / samplesInRing
            }
            if (totalPower <= 1e-12) return -70.0f
            return (-0.691 + 10.0 * log10(totalPower)).toFloat()
        }

        // Stage 1: Absolute threshold gating at -70 LKFS
        // Power corresponding to -70 LKFS: -0.691 + 10 * log10(power) >= -70.0
        // 10 * log10(power) >= -69.309 -> power >= 10^(-6.9309) ≈ 1.1724e-7
        val absThresholdPower = 10.0.pow((-70.0 + 0.691) / 10.0)
        val passedAbs = blockPowers.filter { it >= absThresholdPower }
        if (passedAbs.isEmpty()) return -70.0f

        val zAvgAbs = passedAbs.average()
        val absLoudness = -0.691 + 10.0 * log10(zAvgAbs)

        // Stage 2: Relative threshold gating at -10 LU relative to Stage 1
        val relThresholdLufs = absLoudness - 10.0
        val relThresholdPower = 10.0.pow((relThresholdLufs + 0.691) / 10.0)
        val passedRel = passedAbs.filter { it >= relThresholdPower }
        if (passedRel.isEmpty()) return absLoudness.toFloat()

        val zAvgRel = passedRel.average()
        val integratedLufs = -0.691 + 10.0 * log10(zAvgRel)
        return integratedLufs.toFloat()
    }

    fun reset() {
        filters.forEach { it.reset() }
        for (c in 0 until 2) ringBuffer[c].fill(0.0)
        ringPos = 0
        samplesInRing = 0
        hopCounter = 0
        blockPowers.clear()
    }
}
