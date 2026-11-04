package io.github.mochilabs.mixer

import kotlin.math.abs
import kotlin.math.sqrt

/** Positions are source milliseconds, not bars or musical downbeats. */
data class EnergyPoint(val positionMs: Long, val rms: Float)
data class TransitionCandidate(val positionMs: Long, val energy: Float)

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
    private const val BIN_SAMPLES = SOURCE_RATE / 50
    private const val MAX_SAMPLES = SOURCE_RATE * 62
    private const val SILENCE = 0.001f

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
            if (rms <= SILENCE) KeyEstimate.Unknown("silence") else KeyDetector.estimate(mono),
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

}
