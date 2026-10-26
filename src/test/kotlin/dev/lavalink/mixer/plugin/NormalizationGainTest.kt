package dev.lavalink.mixer.plugin

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationGainTest {
    @Test
    fun `fromLufs calculates accurate linear gain to -14 LUFS target`() {
        // -8 LUFS track (loud EDM/pop) -> diff = -14 - (-8) = -6 dB -> 10^(-6/20) = 0.501f
        val gainLoud = NormalizationGain.fromLufs(-8.0f, targetLufs = -14.0f)
        assertEquals(0.5012f, gainLoud.gain, 1e-3f)

        // -14 LUFS track (already at target) -> gain = 1.0f
        val gainTarget = NormalizationGain.fromLufs(-14.0f, targetLufs = -14.0f)
        assertEquals(1.0f, gainTarget.gain, 1e-6f)
    }

    @Test
    fun `fromLufs respects MAX_GAIN unity ceiling to avoid distortion`() {
        // -24 LUFS track (quiet track) -> without boost allowed, clamped to 1.0f (unity)
        val gainQuiet = NormalizationGain.fromLufs(-24.0f, targetLufs = -14.0f, allowBoost = false)
        assertEquals(NormalizationGain.MAX_GAIN, gainQuiet.gain, 1e-6f)
        assertEquals(1.0f, NormalizationGain.MAX_GAIN, 1e-6f)

        // When allowBoost is enabled, clamped to +3 dB (1.41x)
        val gainBoost = NormalizationGain.fromLufs(-24.0f, targetLufs = -14.0f, allowBoost = true)
        assertEquals(1.41f, gainBoost.gain, 1e-2f)
    }

    @Test
    fun `fromLufs clamps excessive attenuation to MIN_GAIN`() {
        // +5 LUFS (extremely distorted hot signal) -> -19 dB attenuation, clamped to MIN_GAIN (0.25f / -12 dB)
        val gainExtreme = NormalizationGain.fromLufs(5.0f, targetLufs = -14.0f)
        assertEquals(NormalizationGain.MIN_GAIN, gainExtreme.gain, 1e-6f)
    }

    @Test
    fun `fromLufs returns unity for silent track below -70 LUFS gate`() {
        val gainSilent = NormalizationGain.fromLufs(-75.0f, targetLufs = -14.0f)
        assertEquals(1.0f, gainSilent.gain, 1e-6f)

        val gainNan = NormalizationGain.fromLufs(Float.NaN, targetLufs = -14.0f)
        assertEquals(1.0f, gainNan.gain, 1e-6f)
    }
    @Test
    fun `LoudnessMeter measures 1 kHz 0 dBFS sine wave at -3 LUFS per BS1770`() {
        // ITU-R BS.1770 standard: 0 dBFS 1 kHz (997 Hz) sine wave applied to the left (or right) channel produces -3.01 LKFS.
        // When applied to both left and right, power is doubled (+3.01 dB), producing 0.00 LKFS.
        val sampleRate = 48000
        val meter = LoudnessMeter(sampleRate)
        val seconds = 2
        val totalSamples = sampleRate * seconds
        val left = FloatArray(totalSamples)
        val right = FloatArray(totalSamples)

        val freq = 997.0
        for (i in 0 until totalSamples) {
            val sample = sin(2.0 * PI * freq * i / sampleRate).toFloat()
            left[i] = sample
            right[i] = 0f // applied to left channel only
        }

        var offset = 0
        while (offset < totalSamples) {
            val chunkLen = minOf(1920, totalSamples - offset)
            val chunkL = FloatArray(chunkLen)
            val chunkR = FloatArray(chunkLen)
            System.arraycopy(left, offset, chunkL, 0, chunkLen)
            System.arraycopy(right, offset, chunkR, 0, chunkLen)
            meter.feed(arrayOf(chunkL, chunkR), 0, chunkLen)
            offset += chunkLen
        }

        val measured = meter.finish()
        assertEquals(-3.01f, measured, 0.2f)
    }

    fun `LoudnessMeter returns -70 LUFS for digital silence`() {
        val meter = LoudnessMeter(48000)
        val silence = arrayOf(FloatArray(48000), FloatArray(48000))
        meter.feed(silence, 0, 48000)
        val measured = meter.finish()
        assertEquals(-70.0f, measured, 1e-3f)
    }

    @Test
    fun `LoudnessMeter returns -70 LUFS for empty input`() {
        val meter = LoudnessMeter(48000)
        assertEquals(-70.0f, meter.finish(), 1e-3f)
    }
}
