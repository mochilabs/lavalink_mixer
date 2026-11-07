package io.github.mochilabs.mixer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Octave (half/double tempo) regressions. Eighth-note hats and bass at 85-110 BPM
 * used to read as 170-220 BPM, and jittered 190 BPM drums as 95 BPM.
 * Synthetic patterns only; they do not prove accuracy on real music.
 */
class BpmOctaveTest {
    private val sr = 48000

    private fun kick(buf: FloatArray, at: Int, amp: Float) {
        for (k in 0 until (0.12 * sr).toInt()) {
            val i = at + k; if (i < 0 || i >= buf.size) continue
            buf[i] += amp * exp(-k / (0.03 * sr)).toFloat() * sin(2 * PI * 60.0 * k / sr).toFloat()
        }
    }
    private fun snare(buf: FloatArray, at: Int, amp: Float, seed: Int) {
        val r = Random(seed)
        for (k in 0 until (0.08 * sr).toInt()) {
            val i = at + k; if (i < 0 || i >= buf.size) continue
            val env = exp(-k / (0.02 * sr)).toFloat()
            buf[i] += amp * env * (sin(2 * PI * 200.0 * k / sr).toFloat() * 0.3f + (r.nextFloat() * 2 - 1) * 0.7f)
        }
    }
    private fun hat(buf: FloatArray, at: Int, amp: Float, seed: Int) {
        val r = Random(seed)
        for (k in 0 until (0.02 * sr).toInt()) {
            val i = at + k; if (i < 0 || i >= buf.size) continue
            buf[i] += amp * exp(-k / (0.005 * sr)).toFloat() * (r.nextFloat() * 2 - 1)
        }
    }
    private fun bass(buf: FloatArray, at: Int, len: Int, amp: Float, f: Double) {
        for (k in 0 until len) {
            val i = at + k; if (i < 0 || i >= buf.size) continue
            val env = minOf(1.0, k / (0.005 * sr)) * exp(-k / (0.15 * sr))
            buf[i] += (amp * env * sin(2 * PI * f * k / sr)).toFloat()
        }
    }

    private fun chunked(buf: FloatArray) = buf.toList().chunked(1920).map { c -> Array(2) { c.toFloatArray() } }

    private fun pop(bpm: Double, hatDiv: Int, hatAmp: Float, bass8: Boolean, jitterMs: Double = 0.0, snareAmp: Float = 0.4f, seed: Int = 7, seconds: Int = 30): FloatArray {
        val buf = FloatArray(seconds * sr)
        val jr = Random(seed)
        val p = sr * 60.0 / bpm
        var beat = 0
        fun j() = ((jr.nextDouble() * 2 - 1) * jitterMs * sr / 1000).toInt()
        while (beat * p < buf.size - sr) {
            val at = (beat * p).toInt()
            if (beat % 2 == 0) kick(buf, at + j(), 0.85f) else snare(buf, at + j(), snareAmp, beat)
            for (h in 0 until hatDiv) if (hatAmp > 0f) hat(buf, (at + h * p / hatDiv).toInt() + j(), hatAmp, beat * 31 + h)
            if (bass8) for (h in 0 until 2) bass(buf, (at + h * p / 2).toInt() + j(), (p / 2).toInt(), 0.25f, 55.0)
            beat++
        }
        return buf
    }

    private fun dnb(bpm: Double, hatAmp: Float, jitterMs: Double = 0.0, seconds: Int = 30): FloatArray {
        val buf = FloatArray(seconds * sr)
        val jr = Random(3)
        fun j() = ((jr.nextDouble() * 2 - 1) * jitterMs * sr / 1000).toInt()
        val p = sr * 60.0 / bpm
        var beat = 0
        while (beat * p < buf.size - sr) {
            val at = (beat * p).toInt()
            when (beat % 4) {
                0 -> kick(buf, at + j(), 0.85f)
                1, 3 -> snare(buf, at + j(), 0.45f, beat)
                2 -> kick(buf, (at + p / 2).toInt() + j(), 0.85f)
            }
            for (h in 0 until 2) hat(buf, (at + h * p / 2).toInt() + j(), hatAmp, beat * 17 + h)
            beat++
        }
        return buf
    }

    @Test
    fun `octave-ambiguous drum patterns resolve to the reference tempo`() {
        val cases = listOf(
            Triple("pop100 8th hat 0.15", 100.0, pop(100.0, 2, 0.15f, false)),
            Triple("pop100 8th hat 0.30 +bass8", 100.0, pop(100.0, 2, 0.30f, true)),
            Triple("pop100 16th hat 0.20", 100.0, pop(100.0, 4, 0.20f, false)),
            Triple("pop95 16th hat 0.25 +bass8", 95.0, pop(95.0, 4, 0.25f, true)),
            Triple("pop100 j15 8th hat 0.3 bass8", 100.0, pop(100.0, 2, 0.30f, true, jitterMs = 15.0)),
            Triple("pop110 j10 8th hat 0.3 bass8", 110.0, pop(110.0, 2, 0.30f, true, jitterMs = 10.0)),
            Triple("pop85 8th hat 0.4 bass8", 85.0, pop(85.0, 2, 0.40f, true)),
            Triple("pop128 8th hat 0.3 bass8", 128.0, pop(128.0, 2, 0.30f, true)),
            Triple("pop190 8th hat 0.15", 190.0, pop(190.0, 2, 0.15f, false)),
            Triple("pop190 no hat", 190.0, pop(190.0, 1, 0.0f, false)),
            Triple("pop190 j10 hat0.1", 190.0, pop(190.0, 2, 0.10f, false, jitterMs = 10.0)),
            Triple("pop190 j15 nohat", 190.0, pop(190.0, 1, 0.0f, false, jitterMs = 15.0)),
            Triple("pop180 j15 hat0.15", 180.0, pop(180.0, 1, 0.15f, false, jitterMs = 15.0)),
            Triple("pop170 j12 nohat seed2", 170.0, pop(170.0, 1, 0.0f, false, jitterMs = 12.0, seed = 2)),
            Triple("pop160 j15 nohat", 160.0, pop(160.0, 1, 0.0f, false, jitterMs = 15.0)),
            Triple("pop140 j15 8th hat 0.2", 140.0, pop(140.0, 2, 0.2f, false, jitterMs = 15.0)),
            Triple("pop100 8th hat0.5 bass8", 100.0, pop(100.0, 2, 0.5f, true)),
            Triple("pop100 bass8 only", 100.0, pop(100.0, 1, 0.0f, true)),
            Triple("pop120 8th hat0.4 bass8 snare0.6", 120.0, pop(120.0, 2, 0.4f, true, snareAmp = 0.6f)),
            Triple("pop90 16th hat0.3 bass8 j10", 90.0, pop(90.0, 4, 0.3f, true, jitterMs = 10.0)),
            Triple("alt kick 125 .9/.35", 125.0, run { val b = FloatArray(30 * sr); val p = sr * 60.0 / 125; var k = 0; while (k * p < b.size) { kick(b, (k * p).toInt(), if (k % 2 == 0) 0.9f else 0.35f); k++ }; b }),
            Triple("alt kick 110 .9/.35", 110.0, run { val b = FloatArray(30 * sr); val p = sr * 60.0 / 110; var k = 0; while (k * p < b.size) { kick(b, (k * p).toInt(), if (k % 2 == 0) 0.9f else 0.35f); k++ }; b }),
            Triple("alt kick 150 .9/.35", 150.0, run { val b = FloatArray(30 * sr); val p = sr * 60.0 / 150; var k = 0; while (k * p < b.size) { kick(b, (k * p).toInt(), if (k % 2 == 0) 0.9f else 0.35f); k++ }; b }),
            Triple("dnb174 8th hat 0.15", 174.0, dnb(174.0, 0.15f)),
            Triple("dnb174 j10", 174.0, dnb(174.0, 0.15f, jitterMs = 10.0)),
            Triple("dnb190 8th hat 0.15", 190.0, dnb(190.0, 0.15f)),
        )
        val failures = cases.mapNotNull { (name, ref, buf) ->
            val grid = BpmDetector.analyzeGrid(chunked(buf), sampleRate = sr)
            println("$name -> ${grid?.bpm} (reference $ref)")
            if (grid != null && abs(grid.bpm - ref) <= 1.5) null else "$name: ${grid?.bpm}"
        }
        assertTrue(failures.isEmpty(), "wrong tempo: $failures")
    }

    /**
     * Known limit: a fast backbeat whose snare is much weaker than the kick looks
     * like a half-tempo beat with eighth-note subdivisions and reads as half time.
     * Recorded here so a change in this behavior is visible, not asserted as correct.
     */
    @Test
    fun `fast backbeat with a weak snare reads as half time`() {
        val cases = listOf(
            Triple("pop190 snare0.25 hat0.15", 190.0, pop(190.0, 2, 0.15f, false, snareAmp = 0.25f)),
            Triple("pop180 snare0.3 hat0.25 j10", 180.0, pop(180.0, 2, 0.25f, false, jitterMs = 10.0, snareAmp = 0.3f)),
        )
        for ((name, ref, buf) in cases) {
            val grid = BpmDetector.analyzeGrid(chunked(buf), sampleRate = sr)
            println("$name -> ${grid?.bpm} (reference $ref, known half-time reading)")
            assertTrue(grid != null && abs(grid.bpm - ref / 2) <= 1.5, "$name: ${grid?.bpm}")
        }
    }
}
