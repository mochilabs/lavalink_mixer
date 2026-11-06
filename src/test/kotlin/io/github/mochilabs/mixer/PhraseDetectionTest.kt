package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhraseDetectionTest {
    private val sampleRate = 48000

    @Test
    fun `detects phrase boundary and confidence on 8-bar energy shift`() {
        val bpm = 120f
        val periodSamples = (sampleRate * 60.0 / bpm).toInt()
        val barSamples = periodSamples * 4 // 2.0s per bar
        val totalBars = 16
        val totalSamples = barSamples * totalBars // 32 seconds
        val buffer = FloatArray(totalSamples)

        // Bar 0..7: Quiet intro
        for (bar in 0 until 8) {
            val barStart = bar * barSamples
            for (i in 0 until barSamples) {
                buffer[barStart + i] = 0.05f
            }
        }
        // Bar 8..15: Loud drop
        for (bar in 8 until 16) {
            val barStart = bar * barSamples
            for (i in 0 until barSamples) {
                buffer[barStart + i] = 0.40f
            }
        }

        val grid = BeatGrid(bpm, 0L, 0.9f)
        val features = TransitionFeatureAnalyzer.analyze(listOf(arrayOf(buffer)), windowStartMs = 0L, grid = grid)

        assertTrue(features.phraseConfidence >= 0.5f, "phrase confidence must be >= 0.5 on clear 8-bar drop, got ${features.phraseConfidence}")
        // Phrase boundary should land close to bar 8 (8 bars * 2000ms = 16000ms)
        assertTrue(features.phraseBoundaryMs in 15000L..17000L, "phrase boundary should be near 16000ms, got ${features.phraseBoundaryMs}")
    }
}
