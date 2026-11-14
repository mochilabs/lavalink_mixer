package io.github.mochilabs.mixer

import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkedTimescaleFilterTest {
    private class Collector : FloatPcmAudioFilter {
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            for (i in offset until offset + length) {
                left += input[0][i]
                right += input[1][i]
            }
        }
        override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    /** A partly correlated stereo signal: shared tones plus an independent per-channel part. */
    private fun stereoInput(frames: Int): Array<FloatArray> {
        val rnd = Random(7)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        var nl = 0f
        var nr = 0f
        for (i in 0 until frames) {
            val t = i / 48000.0
            val shared = (0.3 * sin(2 * PI * 110 * t) + 0.2 * sin(2 * PI * 233 * t) + 0.1 * sin(2 * PI * 587 * t)).toFloat()
            // Low-passed noise keeps the independent part in the band WSOLA correlates.
            nl = nl * 0.95f + (rnd.nextFloat() - 0.5f) * 0.1f
            nr = nr * 0.95f + (rnd.nextFloat() - 0.5f) * 0.1f
            left[i] = shared + nl
            right[i] = shared + nr
        }
        return arrayOf(left, right)
    }

    private fun run(filter: (FloatPcmAudioFilter) -> FloatPcmAudioFilter, speed: Double, input: Array<FloatArray>): Collector {
        val out = Collector()
        val f = filter(out)
        when (f) {
            is LinkedTimescaleFilter -> f.setSpeed(speed)
            is TimescalePcmAudioFilter -> f.setSpeed(speed)
        }
        var pos = 0
        while (pos < input[0].size) {
            val n = minOf(960, input[0].size - pos)
            f.process(input, pos, n)
            pos += n
        }
        f.close()
        return out
    }

    /** Worst windowed L/R correlation, skipping the converter's startup latency. */
    private fun worstWindowCorrelation(out: Collector, window: Int = 960): Double {
        var worst = 1.0
        var start = 9600
        while (start + window <= out.left.size) {
            var lr = 0.0; var ll = 0.0; var rr = 0.0
            for (i in start until start + window) {
                val l = out.left[i].toDouble(); val r = out.right[i].toDouble()
                lr += l * r; ll += l * l; rr += r * r
            }
            worst = minOf(worst, lr / sqrt(ll * rr + 1e-12))
            start += window
        }
        return worst
    }

    @Test
    fun `linked converter keeps the stereo image coherent while stretching`() {
        val input = stereoInput(48000 * 6)
        val inputCorr = worstWindowCorrelation(Collector().also { c ->
            for (i in input[0].indices) { c.left += input[0][i]; c.right += input[1][i] }
        })
        val linked = run({ LinkedTimescaleFilter(it, 2, 48000) }, 0.9636, input)
        val perChannel = run({ TimescalePcmAudioFilter(it, 2, 48000) }, 0.9636, input)
        val linkedCorr = worstWindowCorrelation(linked)
        val perChannelCorr = worstWindowCorrelation(perChannel)
        println("input=$inputCorr linked=$linkedCorr perChannel=$perChannelCorr")
        assertTrue(linkedCorr > inputCorr - 0.05, "linked stretch decorrelated L/R: $linkedCorr vs input $inputCorr")
        assertTrue(linkedCorr > perChannelCorr, "linked $linkedCorr should beat per-channel $perChannelCorr")
    }

    @Test
    fun `stretch changes length in the requested direction and keeps channels the same length`() {
        val input = stereoInput(48000 * 4)
        val out = run({ LinkedTimescaleFilter(it, 2, 48000) }, 0.9, input)
        assertEquals(out.left.size, out.right.size)
        val expected = input[0].size / 0.9
        assertTrue(abs(out.left.size - expected) < 48000 * 0.2, "got ${out.left.size}, expected about $expected")
    }

    @Test
    fun `mono input is duplicated into both channels`() {
        val out = Collector()
        val f = LinkedTimescaleFilter(out, 2, 48000)
        f.setSpeed(0.95)
        val mono = FloatArray(48000) { i -> sin(2 * PI * 220 * i / 48000.0).toFloat() * 0.5f }
        var pos = 0
        while (pos < mono.size) { f.process(arrayOf(mono), pos, 960); pos += 960 }
        f.close()
        assertTrue(out.left.isNotEmpty())
        for (i in out.left.indices) assertEquals(out.left[i], out.right[i])
    }
}
