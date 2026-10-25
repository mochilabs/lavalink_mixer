package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the normalization gain path: [GuildMixer.mainNormGain]/[GuildMixer.subNormGain]
 * must multiply onto the fade curve so that a louder track stays attenuated
 * through a crossfade, and an overlay must not inherit the previous
 * crossfade track's gain.
 */
class NormalizationIntegrationTest {
    private fun mixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig())
    }

    @Test
    fun `advance returns main gain multiplied by normalization gain`() {
        val m = mixer()
        m.normalizeForTest(mainGain = 0.5f)
        m.gainsForTest(1f, 0f)
        val gains = m.advance(1, 1L)
        // norm gain 0.5 × fade gain 1.0 = 0.5
        assertEquals(0.5f, gains.main, 1e-6f)
        assertEquals(0f, gains.sub, 1e-6f)
    }

    @Test
    fun `mixer filter applies normalization gain to main audio`() {
        val m = mixer()
        m.normalizeForTest(mainGain = 0.5f)
        m.gainsForTest(1f, 0f)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.8f, -0.4f), floatArrayOf(-0.4f, -0.8f))
        filter.process(input, 0, 2)
        // every sample × 0.5
        assertContentEquals(floatArrayOf(0.4f, -0.2f), input[0])
        assertContentEquals(floatArrayOf(-0.2f, -0.4f), input[1])
    }

    @Test
    fun `mixer filter passes through when normalization disabled`() {
        val m = mixer()
        // Default: mainNormGain = 1f → passthrough
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.1f, -0.2f), floatArrayOf(-0.1f, 0.2f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.1f, -0.2f), input[0])
        assertContentEquals(floatArrayOf(-0.1f, 0.2f), input[1])
    }

    @Test
    fun `normalizeForTest resets both gains`() {
        val m = mixer()
        m.normalizeForTest(0.25f, 0.75f)
        m.gainsForTest(1f, 1f)
        val gains = m.advance(1, 1L)
        // main: fade 1.0 × norm 0.25 = 0.25; sub: fade 1.0 × norm 0.75 = 0.75
        assertEquals(0.25f, gains.main, 1e-6f)
        assertEquals(0.75f, gains.sub, 1e-6f)
    }

    /**
     * Regression guard for the "crossfade clips after resume" report: two
     * hot sources (peaks at 1.0) summed on the equal-power curve reach
     * ~1.41x at the midpoint. The old hard clamp flattened that into a
     * square wave (the audible distortion); the soft limiter must compress
     * it smoothly below full scale and stay symmetric.
     */
    @Test
    fun `hot crossfade mix is limited, not hard-clipped`() {
        val m = mixer()
        // Equal-power midpoint: cos(π/4) = sin(π/4) ≈ 0.707
        m.gainsForTest(0.7071f, 0.7071f)
        m.siphon.push(arrayOf(floatArrayOf(1f, -1f)), 0, 2)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(1f, -1f))
        filter.process(input, 0, 2)
        val hot = input[0][0]
        val cold = input[0][1]
        // Over-limit sum (1.4142) is compressed into the knee above 0.9:
        // x=(1.4142-0.9)/0.1=5.142, 0.9+0.1·(5.142/6.142)=0.9837 — just below
        // full scale and the old hard-clamped flat 1.0 square wave, with no
        // audible dip (only a ~0.14dB reduction).
        assertEquals(0.9837f, hot, 1e-3f)
        // Symmetric for negative peaks.
        assertEquals(-hot, cold, 1e-6f)
        assertTrue(hot < 1f && cold > -1f, "mix reached full scale: $hot")
    }

    /** In-range sums must be untouched: no limiter coloration on normal audio. */
    @Test
    fun `gentle mix passes through unchanged`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0.5f)
        m.siphon.push(arrayOf(floatArrayOf(0.2f)), 0, 1)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.2f))
        filter.process(input, 0, 1)
        // Sum = 0.2 — inside the unit range, so bit-identical output.
        assertEquals(0.2f, input[0][0], 1e-6f)
    }
}
