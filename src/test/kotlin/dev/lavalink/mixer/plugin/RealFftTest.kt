package dev.lavalink.mixer.plugin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealFftTest {
    @Test
    fun `440 Hz tone produces peak at bin 19 for 48kHz sample rate`() {
        val fft = RealFft(2048)
        val sr = 48000
        val freq = 440.0
        val re = FloatArray(2048) { i ->
            (sin(2.0 * PI * freq * i / sr) * fft.hannWindow[i]).toFloat()
        }
        val im = FloatArray(2048)
        fft.transform(re, im)

        val mags = FloatArray(1024)
        fft.magnitudes(re, im, mags)

        // Expected bin = round(440 / (48000 / 2048)) = round(18.77) = 19
        var peakBin = 0
        var maxMag = 0f
        for (i in 0 until 1024) {
            if (mags[i] > maxMag) {
                maxMag = mags[i]
                peakBin = i
            }
        }
        assertEquals(19, peakBin, "peak bin for 440 Hz tone")
        assertTrue(maxMag > 100f, "peak magnitude should be strong")
    }

    @Test
    fun `100 Hz kick bass tone lands in low band (bin 4)`() {
        val fft = RealFft(2048)
        val sr = 48000
        val freq = 100.0
        val re = FloatArray(2048) { i ->
            (sin(2.0 * PI * freq * i / sr) * fft.hannWindow[i]).toFloat()
        }
        val im = FloatArray(2048)
        fft.transform(re, im)

        val mags = FloatArray(1024)
        fft.magnitudes(re, im, mags)

        // Expected bin = round(100 / (48000 / 2048)) = round(4.26) = 4
        var peakBin = 0
        var maxMag = 0f
        for (i in 0 until 1024) {
            if (mags[i] > maxMag) {
                maxMag = mags[i]
                peakBin = i
            }
        }
        assertEquals(4, peakBin, "peak bin for 100 Hz tone")
    }

    @Test
    fun `DC signal produces peak at bin 0`() {
        val fft = RealFft(2048)
        val re = FloatArray(2048) { 1.0f }
        val im = FloatArray(2048)
        fft.transform(re, im)

        val mags = FloatArray(1024)
        fft.magnitudes(re, im, mags)

        assertEquals(2048f, mags[0], 1e-3f, "DC magnitude should equal N")
        for (i in 1 until 1024) {
            assertTrue(mags[i] < 1e-4f, "AC bins should be zero for pure DC")
        }
    }
}
