package io.github.mochilabs.mixer

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun keyTone(root: Int, minor: Boolean, samples: Int = 96000): FloatArray {
    val pitches = if (minor) intArrayOf(0, 3, 7) else intArrayOf(0, 4, 7)
    val weights = doubleArrayOf(1.0, 0.7, 0.5)
    val frequencies = pitches.map { 440.0 * 2.0.pow((48 + root + it - 69) / 12.0) }
    return FloatArray(samples) { i ->
        (frequencies.indices.sumOf { weights[it] * sin(2 * PI * frequencies[it] * i / 48000) } * 0.1).toFloat()
    }
}

class KeyDetectorTest {
    @Test
    fun `major and minor chords yield transposed keys`() {
        for ((root, minor) in listOf(0 to false, 7 to true, 1 to false, 3 to true)) {
            val result = KeyDetector.analyze(listOf(arrayOf(keyTone(root, minor))))
            val key = assertIs<KeyEstimate.Candidate>(result.estimate, "$root minor=$minor")
            assertEquals(root, key.pitchClass)
            assertEquals(if (minor) KeyMode.MINOR else KeyMode.MAJOR, key.mode)
            assertTrue(key.confidence.isFinite() && key.confidence in 0f..1f)
            assertEquals(2000L, result.windowEndMs)
        }
    }

    @Test
    fun `silence noise single tones and short audio stay unknown`() {
        val random = Random(42)
        val fixtures = listOf(
            FloatArray(96000),
            FloatArray(96000) { random.nextFloat() * 0.2f - 0.1f },
            FloatArray(96000) { (0.2 * sin(2 * PI * 440 * it / 48000)).toFloat() },
            FloatArray(100),
            FloatArray(96000) { Float.NaN },
        )
        for (pcm in fixtures) assertIs<KeyEstimate.Unknown>(KeyDetector.analyze(listOf(arrayOf(pcm))).estimate)
        assertEquals("empty_audio", assertIs<KeyEstimate.Unknown>(KeyDetector.analyze(emptyList()).estimate).reason)
    }

    @Test
    fun `analysis respects sample bound and agrees with transition key`() {
        val tone = keyTone(7, true)
        val bounded = KeyDetector.analyze(listOf(arrayOf(tone)), maxSamples = 48000)
        assertEquals(1000L, bounded.windowEndMs)
        assertEquals(7, assertIs<KeyEstimate.Candidate>(bounded.estimate).pitchClass)
        assertEquals(TransitionFeatureAnalyzer.analyze(listOf(arrayOf(tone))).key,
            KeyDetector.analyze(listOf(arrayOf(tone))).estimate)
    }
}
