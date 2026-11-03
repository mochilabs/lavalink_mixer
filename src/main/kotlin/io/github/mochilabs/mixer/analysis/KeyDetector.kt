package io.github.mochilabs.mixer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

enum class KeyMode { MAJOR, MINOR }
sealed interface KeyEstimate {
    data class Unknown(val reason: String) : KeyEstimate
    data class Candidate(val pitchClass: Int, val mode: KeyMode, val confidence: Float) : KeyEstimate
}

data class KeyAnalysis(val windowStartMs: Long, val windowEndMs: Long, val estimate: KeyEstimate)

/** Bounded chroma/profile analysis on isolated 48 kHz PCM, shared with transition analysis. */
object KeyDetector {
    private const val SOURCE_RATE = 48000
    private const val RATE = 12000
    private const val WINDOW = 4096
    private val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun analyze(frames: List<Array<FloatArray>>, maxSamples: Int = SOURCE_RATE * 60): KeyAnalysis {
        val count = frames.sumOf { frame -> frame.minOfOrNull { it.size }?.toLong() ?: 0L }
            .coerceAtMost(maxSamples.coerceIn(1, SOURCE_RATE * 60).toLong()).toInt()
        val mono = FloatArray(count / 4)
        var sample = 0
        var sum = 0.0
        var energy = 0.0
        for (frame in frames) {
            val size = frame.minOfOrNull { it.size } ?: 0
            for (i in 0 until size) {
                if (sample >= mono.size * 4) break
                val value = frame.sumOf { channel -> (channel[i].takeIf { it.isFinite() } ?: 0f).toDouble() } / frame.size
                sum += value
                energy += value * value
                sample++
                if (sample % 4 == 0) {
                    mono[sample / 4 - 1] = (sum / 4).toFloat()
                    sum = 0.0
                }
            }
            if (sample >= mono.size * 4) break
        }
        val estimate = when {
            count == 0 -> KeyEstimate.Unknown("empty_audio")
            mono.size < WINDOW -> KeyEstimate.Unknown("short_window")
            sqrt(energy / sample.coerceAtLeast(1)) <= 0.001 -> KeyEstimate.Unknown("silence")
            else -> estimate(mono)
        }
        return KeyAnalysis(0L, sample * 1000L / SOURCE_RATE, estimate)
    }

    internal fun estimate(mono: FloatArray): KeyEstimate {
        if (mono.size < WINDOW) return KeyEstimate.Unknown("short_window")
        val chroma = DoubleArray(12)
        // Goertzel bins at equal-tempered pitches C3..B5, with a Hann window.
        // At most 48 windows regardless of track length; no extra decoder or DSP dependency.
        val windows = minOf(48, 1 + (mono.size - WINDOW) / (WINDOW / 2))
        val hann = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1)) }
        for (w in 0 until windows) {
            val offset = if (windows == 1) 0 else ((mono.size - WINDOW).toLong() * w / (windows - 1)).toInt()
            for (midi in 48..83) {
                val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val coefficient = 2 * cos(2 * PI * frequency / RATE)
                var previous = 0.0
                var beforePrevious = 0.0
                for (i in 0 until WINDOW) {
                    val next = mono[offset + i] * hann[i] + coefficient * previous - beforePrevious
                    beforePrevious = previous
                    previous = next
                }
                val power = (previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious).coerceAtLeast(0.0)
                chroma[midi % 12] += sqrt(power)
            }
        }
        val total = chroma.sum()
        if (total < 1e-5) return KeyEstimate.Unknown("no_tonal_energy")
        val probabilities = chroma.map { it / total }
        // Single tones and broadly flat spectra do not establish a musical key.
        if (probabilities.count { it >= 0.08 } < 3 || probabilities.max() < 0.12) return KeyEstimate.Unknown("ambiguous_chroma")
        data class Score(val root: Int, val mode: KeyMode, val value: Double)
        val scores = buildList {
            for (root in 0..11) for ((mode, profile) in listOf(KeyMode.MAJOR to major, KeyMode.MINOR to minor)) {
                add(Score(root, mode, correlation(chroma, DoubleArray(12) { profile[(it - root + 12) % 12] })))
            }
        }.sortedByDescending { it.value }
        val best = scores.first()
        val margin = best.value - scores[1].value
        if (best.value < 0.5 || margin < 0.04) return KeyEstimate.Unknown("ambiguous_key")
        return KeyEstimate.Candidate(best.root, best.mode, (best.value * (margin / 0.2).coerceIn(0.0, 1.0)).toFloat())
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val am = a.average()
        val bm = b.average()
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += (a[i] - am) * (b[i] - bm)
            aa += (a[i] - am) * (a[i] - am)
            bb += (b[i] - bm) * (b[i] - bm)
        }
        return if (aa * bb <= 1e-12) 0.0 else dot / sqrt(aa * bb)
    }
}
