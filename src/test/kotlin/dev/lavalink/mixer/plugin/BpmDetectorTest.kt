package dev.lavalink.mixer.plugin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmDetectorTest {
    private val sr = 48000
    private val samples = 30 * sr

    /** Mono click track: a short 1.0 pulse on every beat, optional lead-in silence. */
    private fun clickTrack(bpm: Float, seconds: Int, offsetMs: Long = 0L, amp: Float = 1f): List<Array<FloatArray>> {
        val period = (sr * 60f / bpm).toInt()
        val total = seconds * sr
        val buf = FloatArray(total)
        val offset = ((offsetMs * sr) / 1000).toInt()
        var pos = offset
        while (pos + 100 < total) {
            for (i in 0 until 100) buf[pos + i] = amp
            pos += period
        }
        return listOf(arrayOf(buf))
    }

    private fun near(actual: Float, expected: Float, tol: Float) {
        assertTrue(abs(actual - expected) <= tol, "expected ~$expected, got $actual")
    }

    private fun chunked(buf: FloatArray): List<Array<FloatArray>> =
        buf.toList().chunked(1920).map { c -> Array(2) { c.toFloatArray() } }

    private fun addKick(buf: FloatArray, at: Int, amp: Float = 0.8f) {
        for (k in 0 until (0.12 * sr).toInt()) {
            val i = at + k
            if (i >= buf.size) break
            buf[i] += amp * exp(-k / (0.03 * sr)).toFloat() * sin(2 * PI * 60.0 * k / sr).toFloat()
        }
    }

    private fun kickTrack(bpm: Double, firstMs: Int, jitterMs: Double = 0.0, seed: Int = 1): FloatArray {
        val r = Random(seed)
        val buf = FloatArray(samples) { (r.nextFloat() * 2 - 1) * 0.02f }
        var t = firstMs * sr / 1000.0
        while (t < samples) {
            val j = (r.nextFloat() * 2 - 1) * jitterMs / 1000.0 * sr
            addKick(buf, (t + j).toInt().coerceAtLeast(0))
            t += 60.0 / bpm * sr
        }
        return buf
    }

    private fun addSnare(buf: FloatArray, at: Int, amp: Float = 0.4f, seed: Int = 1) {
        val r = Random(seed)
        for (k in 0 until (0.08 * sr).toInt()) {
            val i = at + k
            if (i >= buf.size) break
            val env = exp(-k / (0.02 * sr)).toFloat()
            val tone = sin(2 * PI * 200.0 * k / sr).toFloat() * 0.3f
            val noise = (r.nextFloat() * 2 - 1) * 0.7f
            buf[i] += amp * env * (tone + noise)
        }
    }

    private fun popDrumTrack(bpm: Double, seconds: Int = 10): FloatArray {
        val total = seconds * sr
        val buf = FloatArray(total)
        val periodSamples = sr * 60.0 / bpm
        var t = 0.0
        var beat = 0
        while (t < total - sr) {
            val at = t.toInt().coerceIn(0, total - 1)
            if (beat % 2 == 0) {
                addKick(buf, at, 0.85f)
            } else {
                addSnare(buf, at, 0.40f, seed = beat)
            }
            beat++
            t += periodSamples
        }
        return buf
    }

    @Test
    fun `pop drum track at 120 bpm is detected as 120 bpm not 60 bpm`() {
        val buf = popDrumTrack(120.0)
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        println("Detected BPM: ${result.bpm}, confidence: ${result.confidence}")
        near(result.bpm, 120f, 1.5f)
    }

    @Test
    fun `pop drum track at 128 bpm is detected as 128 bpm not 64 bpm`() {
        val buf = popDrumTrack(128.0)
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        println("Detected BPM: ${result.bpm}, confidence: ${result.confidence}")
        near(result.bpm, 128f, 1.5f)
    }
    @Test
    fun `pop drum track at 80 bpm is detected as 80 bpm not 160 bpm`() {
        val buf = popDrumTrack(80.0)
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        println("Detected 80 BPM track as: ${result.bpm}, confidence: ${result.confidence}")
        near(result.bpm, 80f, 1.5f)
    }

    @Test
    fun `pop drum track at 90 bpm is detected as 90 bpm not 180 bpm`() {
        val buf = popDrumTrack(90.0)
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        println("Detected 90 BPM track as: ${result.bpm}, confidence: ${result.confidence}")
        near(result.bpm, 90f, 1.5f)
    }
    @Test
    fun `120 bpm click track detected`() {
        val r = BpmDetector.analyze(clickTrack(120f, 8))
        assertNotNull(r, "no BPM detected")
        near(r.bpm, 120f, 1.0f)
        val periodMs = (60_000.0 / r.bpm).toLong()
        assertEquals(0L, r.firstBeatMs % periodMs)
        assertTrue(r.confidence > 0.9f, "confidence=${r.confidence}")
    }

    @Test
    fun `150 bpm click track detected`() {
        val r = BpmDetector.analyze(clickTrack(150f, 8))
        assertNotNull(r, "no BPM detected")
        near(r.bpm, 150f, 1.5f)
        assertTrue(r.confidence > 0.9f, "confidence=${r.confidence}")
    }

    @Test
    fun `first beat offset reported`() {
        val r = BpmDetector.analyze(clickTrack(120f, 8, offsetMs = 500L))
        assertNotNull(r, "no BPM detected")
        assertEquals(500L, r.firstBeatMs)
        near(r.bpm, 120f, 1.0f)
    }

    @Test
    fun `silence yields null`() {
        val silence = listOf(Array(1) { FloatArray(sr * 4) })
        assertNull(BpmDetector.analyze(silence))
    }

    @Test
    fun `too short to correlate yields null`() {
        val short = listOf(Array(1) { FloatArray(sr / 2) })
        assertNull(BpmDetector.analyze(short))
    }

    @Test
    fun `steady kick track is detected with high confidence`() {
        val r = BpmDetector.analyze(chunked(kickTrack(120.0, 1000)))
        assertNotNull(r)
        assertTrue(abs(r.bpm - 120f) < 1.5f, "bpm=${r.bpm}")
        assertTrue(abs(r.firstBeatMs - 1000L) <= 20L, "firstBeat=${r.firstBeatMs}")
        assertTrue(r.confidence > 0.9f, "confidence=${r.confidence}")
    }

    @Test
    fun `humanized pulse scores well above non-rhythmic audio`() {
        val pulse = BpmDetector.analyze(chunked(kickTrack(120.0, 1000, jitterMs = 12.0, seed = 2)))
        assertNotNull(pulse)
        assertTrue(pulse.confidence > 0.25f, "confidence=${pulse.confidence}")
    }

    @Test
    fun `white noise has no beat confidence`() {
        val r = Random(3)
        val result = BpmDetector.analyze(chunked(FloatArray(samples) { (r.nextFloat() * 2 - 1) * 0.1f }))
        // Either no result or a clearly low score; never a confident beat.
        assertTrue((result?.confidence ?: 0f) < 0.15f, "confidence=${result?.confidence}")
    }

    @Test
    fun `irregular onsets have no beat confidence`() {
        val r = Random(5)
        val buf = FloatArray(samples)
        var t = sr
        while (t < samples) {
            addKick(buf, t)
            t += ((0.2 + r.nextFloat() * 1.2) * sr).toInt()
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        assertTrue(result.confidence < 0.15f, "confidence=${result.confidence}")
    }

    @Test
    fun `slow swelling noise wash has no beat confidence`() {
        val r = Random(7)
        var y = 0f
        val buf = FloatArray(samples) { i ->
            y += 0.02f * ((r.nextFloat() * 2 - 1) - y)
            (y * 3.0 * (0.6 + 0.4 * sin(2 * PI * 0.15 * (i.toDouble() / sr)))).toFloat()
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertTrue((result?.confidence ?: 0f) < 0.15f, "confidence=${result?.confidence}")
    }

    @Test
    fun `confidence is always within 0 to 1`() {
        val inputs = listOf(
            kickTrack(90.0, 300),
            kickTrack(174.0, 0, jitterMs = 8.0, seed = 9),
            FloatArray(samples) { 1e-4f * (if (it % 2 == 0) 1f else -1f) },
        )
        for (buf in inputs) {
            val c = BpmDetector.analyze(chunked(buf))?.confidence ?: continue
            assertTrue(c in 0f..1f, "confidence=$c")
        }
    }

    @Test
    fun `too-short input yields no result rather than a low-confidence one`() {
        assertNull(BpmDetector.analyze(chunked(FloatArray(sr / 20))))
    }

    @Test
    fun `125 bpm track with alternating kick and snare detects true 125 bpm not half tempo 62_5`() {
        val bpm = 125.0
        val periodSamples = sr * 60.0 / bpm
        val buf = FloatArray(samples)
        var t = 0.0
        var isKick = true
        while (t < samples) {
            val at = t.toInt().coerceIn(0, samples - 1)
            // Kick on odd beats (loud, 0.9), Snare on even beats (moderate, 0.35)
            val amp = if (isKick) 0.9f else 0.35f
            addKick(buf, at, amp)
            isKick = !isKick
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result, "should detect tempo")
        assertTrue(abs(result.bpm - 125f) <= 2.0f, "expected ~125 BPM, got ${result.bpm}")
    }

    private fun addHiHat(buf: FloatArray, at: Int, amp: Float = 0.08f, seed: Int = 1) {
        val r = Random(seed)
        val len = minOf((0.02 * sr).toInt(), buf.size - at)
        for (k in 0 until len) {
            val env = exp(-k / (0.005 * sr)).toFloat()
            buf[at + k] += amp * env * (r.nextFloat() * 2 - 1)
        }
    }

    @Test
    fun `pop drum track with 8th-note hats at 70 bpm is detected as 70 bpm not 140 bpm`() {
        val bpm = 70.0
        val periodSamples = sr * 60.0 / bpm
        val buf = FloatArray(samples)
        var t = 0.0
        var beat = 0
        while (t < samples - sr) {
            val at = t.toInt().coerceIn(0, samples - 1)
            if (beat % 2 == 0) {
                addKick(buf, at, 0.85f)
            } else {
                addSnare(buf, at, 0.40f, seed = beat)
            }
            // 8th-note hi-hat between every beat
            val hatAt = (at + periodSamples / 2).toInt().coerceIn(0, samples - 1)
            addHiHat(buf, hatAt, 0.08f, seed = beat * 10)
            beat++
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result, "no BPM detected")
        near(result.bpm, 70f, 1.5f)
    }

    @Test
    fun `mid-song window with continuous audio reports true first onset not 0ms`() {
        val buf = FloatArray(samples)
        // Background continuous pad/drone playing from sample 0 (amplitude 0.25)
        for (i in 0 until samples) {
            buf[i] = 0.25f * sin(2.0 * PI * 220.0 * i / sr).toFloat()
        }
        // First real transient drum hit enters at 500ms
        val firstHitSample = (0.500 * sr).toInt()
        val periodSamples = sr * 60.0 / 120.0
        var t = firstHitSample.toDouble()
        while (t < samples - sr) {
            addKick(buf, t.toInt(), 0.9f)
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        near(result.bpm, 120f, 1.5f)
        // firstBeatMs must detect the 500ms rise, NOT the 0ms window edge
        assertEquals(500L, result.firstBeatMs)
    }

    @Test
    fun `synthetic pulse with 0_3 percent tempo error maintains under 15ms phase drift across 30 seconds`() {
        // True tempo is 120.0 BPM. Ground truth grid has beats at k * 500ms.
        val track = clickTrack(120.0f, 30)
        val grid = BpmDetector.analyzeGrid(track, windowStartMs = 0L)
        assertNotNull(grid)
        near(grid.bpm, 120.0f, 0.5f)

        // Evaluate phase alignment at 30 seconds (60th beat = 30,000ms)
        val periodMs = 60_000.0 / grid.bpm
        val k60 = Math.round((30_000.0 - grid.phaseMs) / periodMs)
        val predictedMs = grid.phaseMs + k60 * periodMs
        val driftMs = abs(predictedMs - 30_000.0)
        assertTrue(driftMs < 15.0, "expected drift < 15ms across 30s, got $driftMs ms")
    }

    @Test
    fun `70 bpm hip-hop with alternating kick and snare detects 70 bpm`() {
        val bpm = 70.0
        val periodSamples = sr * 60.0 / bpm
        val buf = FloatArray(samples)
        var t = 0.0
        var isKick = true
        while (t < samples - sr) {
            val at = t.toInt().coerceIn(0, samples - 1)
            val amp = if (isKick) 0.90f else 0.40f
            addKick(buf, at, amp)
            isKick = !isKick
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        near(result.bpm, 70f, 1.5f)
    }

    @Test
    fun `3_4 waltz rhythm detects correct quarter-note tempo`() {
        val bpm = 120.0
        val periodSamples = sr * 60.0 / bpm
        val buf = FloatArray(samples)
        var t = 0.0
        var beat = 0
        while (t < samples - sr) {
            val at = t.toInt().coerceIn(0, samples - 1)
            if (beat % 3 == 0) {
                // Downbeat (strong kick)
                addKick(buf, at, 0.90f)
            } else {
                // Beats 2 & 3 (softer chorda/snare)
                addSnare(buf, at, 0.40f, seed = beat)
            }
            beat++
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        near(result.bpm, 120f, 2.0f)
    }

    @Test
    fun `swing shuffle pulse detects underlying tempo`() {
        val bpm = 110.0
        val periodSamples = sr * 60.0 / bpm
        val buf = FloatArray(samples)
        var t = 0.0
        var beat = 0
        while (t < samples - sr) {
            val at = t.toInt().coerceIn(0, samples - 1)
            addKick(buf, at, 0.85f)
            // Shuffle 8th: triplet swing timing (+ 2/3 of beat period)
            val shuffleAt = (at + periodSamples * 2.0 / 3.0).toInt().coerceIn(0, samples - 1)
            addHiHat(buf, shuffleAt, 0.08f, seed = beat)
            beat++
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        near(result.bpm, 110f, 2.0f)
    }

    @Test
    fun `tempo drift from 120 to 123 bpm tracks mean tempo`() {
        val buf = FloatArray(samples)
        var t = 0.0
        while (t < samples - sr) {
            val progress = t / samples
            val currentBpm = 120.0 + progress * 3.0 // 120 -> 123
            val currentPeriod = sr * 60.0 / currentBpm
            addKick(buf, t.toInt(), 0.85f)
            t += currentPeriod
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        // Mean tempo is ~121.5 BPM
        assertTrue(result.bpm in 120.0f..123.0f, "expected ~121.5 BPM, got ${result.bpm}")
    }

    @Test
    fun `quiet intro followed by loud drop detects drop tempo and correct onset`() {
        val buf = FloatArray(samples)
        // 10 seconds of very quiet ambient sound (-40 dB, amp ~0.01)
        val r = Random(42)
        val introSamples = 10 * sr
        for (i in 0 until introSamples) {
            buf[i] = 0.01f * (r.nextFloat() * 2 - 1)
        }
        // Loud 128 BPM drop starts at 10.0s (10,000ms)
        val dropBpm = 128.0
        val periodSamples = sr * 60.0 / dropBpm
        var t = introSamples.toDouble()
        while (t < samples - sr) {
            addKick(buf, t.toInt(), 0.90f)
            t += periodSamples
        }
        val result = BpmDetector.analyze(chunked(buf))
        assertNotNull(result)
        near(result.bpm, 128f, 1.5f)
        // First real beat must detect at 10,000ms
        assertEquals(10_000L, result.firstBeatMs)
    }

    @Test
    fun `44_1kHz input detects accurate tempo`() {
        val sr44 = 44100
        val total = 10 * sr44
        val buf = FloatArray(total)
        val bpm = 124.0
        val period = sr44 * 60.0 / bpm
        var t = 0.0
        while (t < total - sr44) {
            val at = t.toInt()
            for (k in 0 until minOf(64, total - at)) buf[at + k] = 1.0f
            t += period
        }
        val frames = listOf(arrayOf(buf))
        val result = BpmDetector.analyze(frames, sampleRate = sr44)
        assertNotNull(result, "44.1kHz detection failed")
        near(result.bpm, 124f, 1.0f)
    }

    @Test
    fun `tempo below 60 bpm yields null or near-zero confidence without throwing`() {
        // 50 BPM pulse (period 1.2s, outside standard 60-200 BPM search range)
        val result = BpmDetector.analyze(clickTrack(50f, 16))
        // Must either be null or have confidence below pulse threshold (< 0.25)
        if (result != null) {
            assertTrue(result.confidence < 0.25f, "confidence should be low for out-of-range tempo: ${result.confidence}")
        }
    }
}
