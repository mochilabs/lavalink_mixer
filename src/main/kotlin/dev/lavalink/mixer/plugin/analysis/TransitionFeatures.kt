package dev.lavalink.mixer.plugin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/** Positions are source milliseconds, not bars or musical downbeats. */
data class EnergyPoint(val positionMs: Long, val rms: Float)
data class TransitionCandidate(val positionMs: Long, val energy: Float)
enum class KeyMode { MAJOR, MINOR }
sealed interface KeyEstimate {
    data class Unknown(val reason: String) : KeyEstimate
    data class Candidate(val pitchClass: Int, val mode: KeyMode, val confidence: Float) : KeyEstimate
}

data class TransitionFeatures(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val energyEnvelope: List<EnergyPoint>,
    val rms: Float,
    val rhythmicStability: Float?,
    val key: KeyEstimate,
    val candidates: List<TransitionCandidate>,
    val leadingSilenceMs: Long = 0L,
    val trailingSilenceMs: Long = 0L,
    val unknownReason: String? = null,
)

/** Bounded offline measurements; never called from the live PCM filter. */
object TransitionFeatureAnalyzer {
    private const val SOURCE_RATE = 48000
    private const val RATE = 12000
    private const val BIN_SAMPLES = SOURCE_RATE / 50
    private const val MAX_SAMPLES = SOURCE_RATE * 62
    private const val SILENCE = 0.001f
    private const val WINDOW = 4096
    private val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun analyze(frames: List<Array<FloatArray>>, windowStartMs: Long = 0L, grid: BeatGrid? = null): TransitionFeatures {
        val count = frames.sumOf { it.firstOrNull()?.size?.toLong() ?: 0L }.coerceAtMost(MAX_SAMPLES.toLong()).toInt()
        if (count == 0) return TransitionFeatures(windowStartMs, windowStartMs, emptyList(), 0f, null,
            KeyEstimate.Unknown("empty_audio"), emptyList(), unknownReason = "empty_audio")
        val mono = FloatArray((count + 3) / 4)
        val envelope = mutableListOf<EnergyPoint>()
        var sample = 0
        var binEnergy = 0.0
        var totalEnergy = 0.0
        var downsampleSum = 0.0
        for (frame in frames) {
            val size = frame.minOfOrNull { it.size } ?: 0
            for (i in 0 until size) {
                if (sample >= count) break
                var value = 0.0
                var power = 0.0
                for (channel in frame) {
                    val x = channel[i].takeIf { it.isFinite() } ?: 0f
                    value += x
                    power += x * x
                }
                value /= frame.size
                power /= frame.size
                downsampleSum += value
                binEnergy += power
                totalEnergy += power
                sample++
                if (sample % 4 == 0) {
                    mono[sample / 4 - 1] = (downsampleSum / 4).toFloat()
                    downsampleSum = 0.0
                }
                if (sample % BIN_SAMPLES == 0) {
                    envelope += EnergyPoint(windowStartMs + (sample - BIN_SAMPLES) * 1000L / SOURCE_RATE, sqrt(binEnergy / BIN_SAMPLES).toFloat())
                    binEnergy = 0.0
                }
            }
            if (sample >= count) break
        }
        val remainder = sample % BIN_SAMPLES
        if (remainder > 0) envelope += EnergyPoint(windowStartMs + (sample - remainder) * 1000L / SOURCE_RATE, sqrt(binEnergy / remainder).toFloat())
        val rms = sqrt(totalEnergy / sample.coerceAtLeast(1)).toFloat()
        val end = windowStartMs + sample * 1000L / SOURCE_RATE
        val leading = envelope.takeWhile { it.rms <= SILENCE }.size * 20L
        val trailing = envelope.asReversed().takeWhile { it.rms <= SILENCE }.size * 20L
        val candidates = mutableListOf<TransitionCandidate>()
        for (region in listOf(envelope.filter { it.positionMs <= windowStartMs + 2000 }, envelope.filter { it.positionMs >= end - 2000 })) {
            var added = 0
            for (point in region.sortedBy { it.rms }) {
                if (candidates.all { abs(it.positionMs - point.positionMs) >= 500L }) {
                    candidates += TransitionCandidate(point.positionMs, point.rms)
                    added++
                }
                if (added == 4 || candidates.size == 8) break
            }
        }
        return TransitionFeatures(
            windowStartMs, end, envelope, rms, stability(envelope, grid),
            if (rms <= SILENCE) KeyEstimate.Unknown("silence") else estimateKey(mono),
            candidates.sortedBy { it.positionMs },
            if (leading >= 100) leading.coerceAtMost(end - windowStartMs) else 0L,
            if (trailing >= 100) trailing.coerceAtMost(end - windowStartMs) else 0L,
            if (sample < SOURCE_RATE) "short_window" else if (rms <= SILENCE) "silence" else null,
        )
    }

    private fun stability(envelope: List<EnergyPoint>, grid: BeatGrid?): Float? {
        if (grid == null || !grid.periodMs.isFinite() || grid.periodMs <= 0) return null
        val lag = (grid.periodMs / 20).toInt().coerceAtLeast(1)
        if (envelope.size < lag * 4) return null
        val mean = envelope.map { it.rms.toDouble() }.average()
        var dot = 0.0
        var aPower = 0.0
        var bPower = 0.0
        for (i in lag until envelope.size) {
            val a = envelope[i].rms - mean
            val b = envelope[i - lag].rms - mean
            dot += a * b
            aPower += a * a
            bPower += b * b
        }
        if (aPower * bPower < 1e-12) return null
        return (dot / sqrt(aPower * bPower)).toFloat().coerceIn(0f, 1f)
    }

    private fun estimateKey(mono: FloatArray): KeyEstimate {
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
