package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationGainTest {
    @Test
    fun `fromRms never boosts above unity`() {
        // A track much quieter than the baseline used to be boosted to
        // MAX_GAIN (4x): that boost then multiplied into the crossfade's
        // equal-power sum and hard-clipped. Attenuation-only now: the
        // quietest track can at most play at the baseline loudness.
        val gain = NormalizationGain.fromRms(0.001f, targetRms = 0.1f)
        assertEquals(NormalizationGain.MAX_GAIN, gain.gain, 1e-6f)
        assertEquals(1f, NormalizationGain.MAX_GAIN, 1e-6f)
    }

    @Test
    fun `fromRms computes linear gain to target when attenuating`() {
        val gain = NormalizationGain.fromRms(0.2f, targetRms = 0.1f)
        assertEquals(0.5f, gain.gain, 1e-6f)
    }

    @Test
    fun `fromRms clamps to MIN_GAIN`() {
        val gain = NormalizationGain.fromRms(1.0f, targetRms = 0.1f)
        assertEquals(NormalizationGain.MIN_GAIN, gain.gain, 1e-6f)
    }

    @Test
    fun `fromRms returns unity for silent track`() {
        val gain = NormalizationGain.fromRms(0f, targetRms = 0.1f)
        assertEquals(1f, gain.gain, 1e-6f)
    }

    @Test
    fun `LoudnessMeter computes RMS across channels`() {
        val meter = LoudnessMeter()
        val frames = arrayOf(
            floatArrayOf(1f, -1f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, -1f),
        )
        meter.feed(frames, 0, 4)
        // 4 samples of 1.0², 4 of 0.0² → RMS = sqrt(4/8) = 0.7071
        assertEquals(0.7071f, meter.finish(), 1e-3f)
    }

    @Test
    fun `LoudnessMeter accumulates across feeds`() {
        val meter = LoudnessMeter()
        meter.feed(arrayOf(floatArrayOf(1f, 1f)), 0, 2)
        meter.feed(arrayOf(floatArrayOf(0f, 0f)), 0, 2)
        // 2 samples of 1.0², 2 of 0.0² → RMS = sqrt(2/4) = 0.7071
        assertEquals(0.7071f, meter.finish(), 1e-3f)
    }

    @Test
    fun `LoudnessMeter returns zero for empty input`() {
        assertEquals(0f, LoudnessMeter().finish(), 1e-9f)
    }
}
