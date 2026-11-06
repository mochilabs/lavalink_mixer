package io.github.mochilabs.mixer

import io.github.mochilabs.mixer.BpmDetector
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownbeatDetectorTest {
    private val sampleRate = 48000

    @Test
    fun `4-4 drum pattern detects downbeat at first kick with high confidence`() {
        val bpm = 120f
        val periodSamples = (sampleRate * 60.0 / bpm).toInt()
        val totalSamples = sampleRate * 12 // 12 seconds = 24 beats = 6 bars
        val buffer = FloatArray(totalSamples)

        // 4/4 pattern: Kick on beat 0 and 2, Snare on beat 1 and 3
        // Kick has heavy low-frequency content (modeled with large amplitude pulse)
        // Snare has high-frequency noise (modeled with smaller sharp pulse)
        for (beat in 0 until 24) {
            val start = beat * periodSamples
            val amplitude = when {
                beat % 4 == 0 -> 1.0f // Downbeat: Kick + primary bar accent
                beat % 4 == 2 -> 0.65f // Back-kick: Secondary kick
                else -> 0.35f          // Snare
            }
            val pulseLen = if (beat % 2 == 0) 256 else 96
            for (i in 0 until pulseLen) {
                if (start + i < totalSamples) {
                    buffer[start + i] = amplitude
                }
            }
        }

        val grid = BpmDetector.analyzeExtendedGrid(listOf(arrayOf(buffer)), sampleRate = sampleRate)
        assertNotNull(grid, "grid must not be null for clear 4/4 drums")
        assertEquals(120f, grid.bpm, 1.0f)
        assertFalse(grid.isNon44Meter, "4/4 beat must not be flagged as non-4/4")
        assertTrue(grid.downbeatConfidence >= 0.5f, "downbeat confidence must be >= 0.5 for clear kicks, got ${grid.downbeatConfidence}")

        // Downbeat should land on beat 0 (mod 4 * periodSamples)
        val barPeriodSamples = periodSamples * 4
        val downbeatOffset = ((grid.downbeatSample % barPeriodSamples) + barPeriodSamples) % barPeriodSamples
        val distToBarZero = minOf(downbeatOffset, barPeriodSamples - downbeatOffset)
        val distMs = distToBarZero.toDouble() * 1000.0 / sampleRate
        assertTrue(distMs < 30.0, "downbeat must align with bar 0 kick, error was ${distMs}ms")
    }

    @Test
    fun `3-4 waltz pattern is flagged as non-4-4 with zero downbeat confidence`() {
        val bpm = 120f
        val periodSamples = (sampleRate * 60.0 / bpm).toInt()
        val totalSamples = sampleRate * 12 // 12 seconds
        val buffer = FloatArray(totalSamples)

        // 3/4 pattern: Kick on beat 0, light beats on 1 and 2
        var beat = 0
        while (beat * periodSamples < totalSamples) {
            val start = beat * periodSamples
            val isKick = (beat % 3 == 0)
            val amplitude = if (isKick) 0.95f else 0.3f
            val pulseLen = if (isKick) 256 else 96
            for (i in 0 until pulseLen) {
                if (start + i < totalSamples) {
                    buffer[start + i] = amplitude
                }
            }
            beat++
        }

        val grid = BpmDetector.analyzeExtendedGrid(listOf(arrayOf(buffer)), sampleRate = sampleRate)
        assertNotNull(grid, "grid should still detect tempo")
        assertTrue(grid.isNon44Meter, "3/4 waltz must be detected as non-4/4")
        assertEquals(0.0f, grid.downbeatConfidence, "downbeat confidence must be zeroed for non-4/4")
    }
}
