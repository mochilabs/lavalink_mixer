package dev.lavalink.mixer.plugin

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Synthetic-only tests for [BpmDetector.analyzeGrid]: the parabolic peak
 * interpolation (tempo resolution well below the 10ms hop), the grid phase
 * anchoring, and the octave-error guard. No fixtures, no network.
 */
class BpmGridTest {
    private val sr = 48000

    /**
     * Mono click track: a 64-sample transient on every beat. [jitterMs] shifts
     * each click by a fresh uniform offset (humanized pulse).
     */
    private fun clickTrack(
        bpm: Float,
        seconds: Int,
        offsetMs: Long = 0L,
        jitterMs: Float = 0f,
        seed: Int = 1,
    ): List<Array<FloatArray>> {
        val r = if (jitterMs > 0f) Random(seed) else null
        val periodSamples = sr * 60.0 / bpm
        val total = seconds * sr
        val buf = FloatArray(total)
        var t = offsetMs * sr / 1000.0
        while (t + 64 < total) {
            val jitter = r?.nextFloat() ?: 0f
            val pos = ((t + jitter * jitterMs * sr / 1000.0).toInt()).coerceIn(0, total - 64)
            for (i in 0 until 64) buf[pos + i] = 1f
            t += periodSamples
        }
        return listOf(arrayOf(buf))
    }

    private fun assertNear(actual: Float, expected: Float, tol: Float, what: String) {
        assertTrue(abs(actual - expected) <= tol, "$what expected ~$expected (±$tol), got $actual")
    }

    // ------------------------------------------------------------------
    // 1. Resolution: interpolated tempo beats the 2%-per-lag-step quantization.
    //    At 100fps, plain argmax around 120 BPM can only land on 117.6/120/122.4;
    //    parabolic refinement must land within +-0.5 BPM.
    // ------------------------------------------------------------------

    @Test
    fun `tempo resolution within half bpm at 120`() = assertBpm(120f)

    @Test
    fun `tempo resolution within half bpm at 100`() = assertBpm(100f)

    @Test
    fun `tempo resolution within half bpm at 87 5`() = assertBpm(87.5f)

    @Test
    fun `tempo resolution within half bpm at 150`() = assertBpm(150f)

    private fun assertBpm(bpm: Float) {
        val grid = BpmDetector.analyzeGrid(clickTrack(bpm, 8))
            ?: fail("no beat grid for $bpm BPM")
        assertNear(grid.bpm, bpm, 0.5f, "bpm")
    }

    // ------------------------------------------------------------------
    // 2. Phase: grid phase follows the first click, mod the beat period.
    // ------------------------------------------------------------------

    @Test
    fun `grid phase anchored at first click`() {
        val grid = BpmDetector.analyzeGrid(clickTrack(120f, 8)) ?: fail("no grid")
        assertNear(grid.phaseMs.toFloat(), 0f, 20f, "phase")
    }

    @Test
    fun `grid phase follows a 250ms offset`() {
        val grid = BpmDetector.analyzeGrid(clickTrack(120f, 8, offsetMs = 250L)) ?: fail("no grid")
        // 250ms offset against a 500ms period: phase must be ~250, not 0 or wrap.
        assertNear(grid.phaseMs.toFloat(), 250f, 20f, "phase")
    }

    // ------------------------------------------------------------------
    // 3. Octave guard: +3ms of jitter must not collapse 120 BPM to 60.
    // ------------------------------------------------------------------

    @Test
    fun `jittered 120 bpm octave stays anchored`() {
        val grid = BpmDetector.analyzeGrid(clickTrack(120f, 16, jitterMs = 3f, seed = 4))
            ?: fail("no grid")
        assertTrue(grid.bpm in 110f..130f, "bpm=${grid.bpm}, expected 110..130")
        assertTrue(grid.confidence > 0.25f, "confidence=${grid.confidence}")
    }

    // ------------------------------------------------------------------
    // 4. Fail-closed on non-pulses; never throws.
    // ------------------------------------------------------------------

    @Test
    fun `pure tone yields null or near-zero confidence`() {
        val sr = 48000
        val buf = FloatArray(8 * sr) { i -> 0.8f * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sr).toFloat() }
        val grid = BpmDetector.analyzeGrid(listOf(arrayOf(buf))) ?: return
        assertTrue(grid.confidence < 0.1f, "tone confidence=${grid.confidence}")
    }

    @Test
    fun `white noise yields null or near-zero confidence`() {
        val r = Random(7)
        val buf = FloatArray(8 * sr) { (r.nextFloat() * 2 - 1) * 0.2f }
        val grid = BpmDetector.analyzeGrid(listOf(arrayOf(buf))) ?: return
        assertTrue(grid.confidence < 0.1f, "noise confidence=${grid.confidence}")
    }

    @Test
    fun `window start offset shifts the grid phase`() {
        val baseGrid = BpmDetector.analyzeGrid(clickTrack(120f, 8)) ?: fail("no base grid")
        val shiftedGrid = BpmDetector.analyzeGrid(
            clickTrack(120f, 8),
            windowStartMs = 15_000L,
        ) ?: fail("no shifted grid")
        assertNear(shiftedGrid.phaseMs.toFloat(), 15_000f + baseGrid.phaseMs, 5f, "shifted phase")
    }
}