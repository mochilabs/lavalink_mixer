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

    @Test
    fun `noninteger hop pulses use adjacent onset evidence instead of half tempo`() {
        for (bpm in listOf(121f, 123f, 124f, 126f, 128f, 131f, 138f, 167f)) {
            for (seconds in listOf(5, 8, 16)) {
                val grid = BpmDetector.analyzeGrid(clickTrack(bpm, seconds)) ?: fail("no grid at $bpm")
                assertNear(grid.bpm, bpm, 0.6f, "$bpm BPM over $seconds seconds")
            }
        }
    }

    @Test
    fun `124 bpm pulse remains correct with a partial scan boundary`() {
        for (offset in listOf(37L, 230L, 451L)) {
            val grid = BpmDetector.analyzeGrid(clickTrack(124f, 5, offsetMs = offset)) ?: fail("no offset grid")
            assertNear(grid.bpm, 124f, 0.6f, "offset pulse")
        }
    }

    @Test
    fun `true slow pulses do not acquire nonexistent intervening beats`() {
        for (bpm in listOf(60f, 62f, 67f, 75f)) {
            val grid = BpmDetector.analyzeGrid(clickTrack(bpm, 16, jitterMs = 3f)) ?: fail("no slow grid")
            assertNear(grid.bpm, bpm, 0.5f, "slow pulse")
        }
    }

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

    @Test
    fun `tail phase preserves global grid alignment across arbitrary window start`() {
        val bpm = 128f
        val periodMs = 60_000.0 / bpm
        // Global track has clicks anchored at t = 100ms + k * periodMs
        val baseOffsetMs = 100L
        val track = clickTrack(bpm, seconds = 10, offsetMs = 0L) // first click at 0 + jitter 0
        // Simulate tail extraction starting at an arbitrary non-beat-aligned timestamp
        val tailStartMs = 182_345L
        val tailGrid = BpmDetector.analyzeGrid(track, windowStartMs = tailStartMs) ?: fail("no tail grid")

        // Any beat predicted by tailGrid should land at tailGrid.phaseMs + k * tailGrid.periodMs
        // In global track coordinates, this must be an integer multiple of periodMs (mod periodMs == 0)
        val errorMs = ((tailGrid.phaseMs % periodMs) + periodMs) % periodMs
        val distanceToGrid = minOf(errorMs, periodMs - errorMs)
        assertTrue(distanceToGrid <= 20.0, "tail grid error=$distanceToGrid ms (expected <= 20ms)")
    }

    @Test
    fun `tail phase with leading silence inside window preserves grid phase`() {
        val bpm = 120f
        val periodMs = 500.0
        // First click is at 1500ms into the tail window
        val track = clickTrack(bpm, seconds = 10, offsetMs = 1500L)
        val tailStartMs = 90_000L
        val tailGrid = BpmDetector.analyzeGrid(track, windowStartMs = tailStartMs) ?: fail("no tail grid")

        // In global track timeline, the first beat was at 90_000 + 1500 = 91,500ms
        // modulo periodMs (500ms), 91,500 % 500 == 0.
        val errorMs = ((tailGrid.phaseMs % periodMs) + periodMs) % periodMs
        val distanceToGrid = minOf(errorMs, periodMs - errorMs)
        assertTrue(distanceToGrid <= 20.0, "tail grid with leading silence error=$distanceToGrid ms")
    }

    @Test
    fun `global phase optimization ignores spurious early onset`() {
        val bpm = 120f
        val periodSamples = (sr * 60.0 / bpm).toInt()
        val total = 10 * sr
        val buf = FloatArray(total)
        // Spurious non-beat click at 60ms
        val spuriousPos = (0.060 * sr).toInt()
        for (i in 0 until 64) buf[spuriousPos + i] = 1f
        // True regular beat starts at 250ms and repeats every 500ms
        var pos = (0.250 * sr).toInt()
        while (pos + 64 < total) {
            for (i in 0 until 64) buf[pos + i] = 1f
            pos += periodSamples
        }
        val grid = BpmDetector.analyzeGrid(listOf(arrayOf(buf)), sampleRate = sr) ?: fail("no grid")
        assertNear(grid.bpm, 120f, 0.5f, "bpm")
        // Phase should align with the true 250ms pulse, not the 60ms rogue click
        assertNear(grid.phaseMs.toFloat(), 250f, 25f, "phase anchored to periodic beats")
    }
}