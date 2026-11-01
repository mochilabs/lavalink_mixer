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

    @Test
    fun `120 bpm click track detected`() {
        val r = BpmDetector.analyze(clickTrack(120f, 8))
        assertNotNull(r, "no BPM detected")
        near(r.bpm, 120f, 1.0f)
        assertEquals(0L, r.firstBeatMs)
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
}
