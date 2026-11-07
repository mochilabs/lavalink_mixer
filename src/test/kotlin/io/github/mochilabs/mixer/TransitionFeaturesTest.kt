package io.github.mochilabs.mixer

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransitionFeaturesTest {
    private fun frames(seconds: Int, signal: (Int) -> Float): List<Array<FloatArray>> {
        val mono = FloatArray(seconds * 48000, signal)
        return mono.asList().chunked(1920).map { chunk -> arrayOf(chunk.toFloatArray(), chunk.toFloatArray()) }
    }

    @Test
    fun `silence and empty scans are explicitly unknown`() {
        val empty = TransitionFeatureAnalyzer.analyze(emptyList(), windowStartMs = 9000)
        assertEquals("empty_audio", empty.unknownReason)
        assertEquals(9000L, empty.windowEndMs)
        val silence = TransitionFeatureAnalyzer.analyze(frames(2) { 0f })
        assertEquals("silence", silence.unknownReason)
        assertIs<KeyEstimate.Unknown>(silence.key)
        assertEquals(2000L, silence.leadingSilenceMs)
        assertEquals(2000L, silence.trailingSilenceMs)
    }

    @Test
    fun `energy and silence boundaries use source positions`() {
        val result = TransitionFeatureAnalyzer.analyze(frames(3) { i ->
            if (i < 48000 || i >= 96000) 0f else (0.2 * sin(2 * PI * 440 * i / 48000)).toFloat()
        }, windowStartMs = 9000)
        assertEquals(9000L, result.windowStartMs)
        assertEquals(12000L, result.windowEndMs)
        assertEquals(1000L, result.leadingSilenceMs)
        assertEquals(1000L, result.trailingSilenceMs)
        assertTrue(kotlin.math.abs(result.rms - 0.2 / sqrt(6.0)) < 0.002)
        assertTrue(result.candidates.size <= 8)
        assertTrue(result.candidates.all { it.positionMs in 9000 until 12000 })
        assertTrue(result.candidates.zipWithNext().all { (a, b) -> b.positionMs - a.positionMs >= 500 })
    }

    @Test
    fun `regular pulses have stable rhythm and irregular pulses do not`() {
        fun pulses(positions: List<Int>) = frames(6) { i ->
            if (positions.any { i in it until it + 960 }) 0.2f else 0f
        }
        val grid = ExtendedBeatGrid(120f, 0L, 0.9f)
        val steady = TransitionFeatureAnalyzer.analyze(pulses((0 until 12).map { it * 24000 }), grid = grid)
        val uneven = TransitionFeatureAnalyzer.analyze(pulses(listOf(0, 6000, 51000, 67000, 99000, 130000, 139000, 200000)), grid = grid)
        assertTrue((steady.rhythmicStability ?: 0f) > 0.95f)
        assertTrue((uneven.rhythmicStability ?: 1f) < 0.45f)
    }

    @Test
    fun `weighted C major pitch classes yield C major candidate`() {
        val weights = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val frequencies = DoubleArray(12) { 261.625565 * Math.pow(2.0, it / 12.0) }
        val result = TransitionFeatureAnalyzer.analyze(frames(2) { i ->
            (weights.indices.sumOf { weights[it] * sin(2 * PI * frequencies[it] * i / 48000) } / 80).toFloat()
        })
        val key = assertIs<KeyEstimate.Candidate>(result.key)
        assertEquals(0, key.pitchClass)
        assertEquals(KeyMode.MAJOR, key.mode)
        assertTrue(key.confidence > 0.6f)
    }

    @Test
    fun `a single tone does not establish a key`() {
        val result = TransitionFeatureAnalyzer.analyze(frames(2) { (0.2 * sin(2 * PI * 440 * it / 48000)).toFloat() })
        assertIs<KeyEstimate.Unknown>(result.key)
    }
}
