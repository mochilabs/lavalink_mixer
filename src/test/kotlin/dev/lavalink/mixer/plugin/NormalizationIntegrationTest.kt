package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
        val gains = m.advance(1)
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
        m.normalizeForTest(0.25f, 3f)
        m.gainsForTest(1f, 1f)
        val gains = m.advance(1)
        // main: fade 1.0 × norm 0.25 = 0.25; sub: fade 1.0 × norm 3.0 = 3.0
        assertEquals(0.25f, gains.main, 1e-6f)
        assertEquals(3f, gains.sub, 1e-6f)
    }
}
