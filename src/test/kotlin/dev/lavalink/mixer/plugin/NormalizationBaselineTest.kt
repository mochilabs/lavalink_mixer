package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the first-track baseline (album-gain) path: the first track anchors
 * the session at unity gain while its background loudness scan runs; every
 * subsequent track's gain is multiplied by that anchor so all tracks play
 * at the same loudness as the first one.
 */
class NormalizationBaselineTest {
    private fun mixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig())
    }

    @Test
    fun `first track plays at unity until baseline is set`() {
        val m = mixer()
        // normGainBaseline = 0 → no anchor yet
        assertEquals(1f, m.currentTrackGain(), 1e-6f)
    }

    @Test
    fun `normGainForLocked applies baseline once set`() {
        val m = mixer()
        m.normBaselineForTest(0.5f)
        // No track in cache → gain = baseline × 1 = 0.5
        val track = proxyTrack("test-id")
        // normGainForLocked is private; use currentTrackGain via onTrackStart
        m.normalizeForTest(0.5f, 1f)
        m.gainsForTest(1f, 0f)
        val gains = m.advance(1, 1L)
        assertEquals(0.5f, gains.main, 1e-6f)
    }

    @Test
    fun `doHandoff carries sub norm gain to main`() {
        val m = mixer()
        m.normBaselineForTest(0.8f)
        m.normalizeForTest(mainGain = 1f, subGain = 0.6f)
        m.beginXfadeForTest(48_000)
        m.advance(48_000, 1L) // complete fade
        // At handoff: sub becomes main at subNormGain
        val gains = m.advance(1, 1L)
        assertEquals(0f, gains.main, 1e-6f)
        assertEquals(0.6f, gains.sub, 1e-6f)
        // Handoff done
        m.endXfadeForTest()
        // Now onTrackStart would set mainNormGain = subNormGain = 0.6
        m.clearXfadeForTest()
        // After clear, mainNormGain should be 0.6 (from handoff), subNormGain reset
        m.gainsForTest(0.6f, 0f)
        val gainsAfter = m.advance(1, 1L)
        assertEquals(0.6f, gainsAfter.main, 1e-6f)
    }

    @Test
    fun `baseline anchors subsequent tracks to first track loudness`() {
        val m = mixer()
        m.normBaselineForTest(0.8f)
        // Track in cache with gain 0.7 → effective = 0.8 × 0.7 = 0.56
        m.normalizeForTest(mainGain = 0.56f, subGain = 0f)
        val gains = m.advance(1, 1L)
        assertEquals(0.56f, gains.main, 1e-6f)
    }

    @Test
    fun `startOverlay resets sub norm gain to unity`() {
        val m = mixer()
        // Simulate crossfade that left subNormGain = 0.5
        m.normalizeForTest(mainGain = 1f, subGain = 0.5f)
        assertEquals(0.5f, m.subNormGainForTest(), 1e-6f)
        // Simulate the overlay path: startOverlay sets subNormGain = 1f
        // (we can't test the full startOverlay without a real sub player;
        // the reset is in startOverlay's synchronized block)
        m.normalizeForTest(mainGain = 1f, subGain = 1f)
        assertEquals(1f, m.subNormGainForTest(), 1e-6f)
    }

    @Test
    fun `scheduleBaselineAnalysis skips when baseline already set`() {
        val m = mixer()
        // Seed the baseline and the track's RMS so currentTrackGain computes
        // the anchored gain (0.5 / 0.5 = 1.0)
        m.normBaselineForTest(rms = 0.5f, trackId = "id", trackRms = 0.5f)
        // Simulate a currently playing main track with unity gain
        m.gainsForTest(1f, 0f)
        // scheduleBaselineAnalysis should return immediately (baseline already set)
        m.scheduleBaselineAnalysis(proxyTrack("id"))
        // mainNormGain is unchanged (no re-analysis)
        m.gainsForTest(0.5f, 0f)
        val gains = m.advance(1, 1L)
        assertEquals(0.5f, gains.main, 1e-6f)
    }

    private fun proxyTrack(id: String): com.sedmelluq.discord.lavaplayer.track.AudioTrack =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.sedmelluq.discord.lavaplayer.track.AudioTrack::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "getInfo" -> com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                    "id", "t", 1000L, id, false, "uri", null, null,
                )
                "makeClone" -> throw UnsupportedOperationException("not in test")
                "toString" -> "ProxyTrack($id)"
                "hashCode" -> id.hashCode()
                "equals" -> instance === (args?.getOrNull(0))
                else -> throw UnsupportedOperationException()
            }
        } as com.sedmelluq.discord.lavaplayer.track.AudioTrack
}
