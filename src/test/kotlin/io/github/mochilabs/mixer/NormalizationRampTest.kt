package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the "loud, then a sudden drop" report: a loudness result that lands
 * after a track started must glide to its gain, and a track whose loudness is
 * unknown must not enter at unity.
 */
class NormalizationRampTest {
    private val rampSamples = GuildMixer.msToSamples(GuildMixer.NORM_RAMP_MS)
    private val chunk = 960

    @Test
    fun `late full-track loudness ramps the playing track instead of stepping`() = withPlayingTrack { m, track ->
        m.normalizationEnabled = true
        m.normalizeForTest(mainGain = 1f)
        m.gainsForTest(1f, 0f)
        val expected = NormalizationGain.fromLufs(-8f, targetLufs = -14f).gain

        m.loudnessForTest(track.identifier, -8f)
        assertEquals(expected, m.currentTrackGain(), 1e-4f)

        val maxStep = (1f - expected) * chunk / rampSamples + 1e-4f
        var previous = 1f
        var advanced = 0
        while (advanced < rampSamples + chunk) {
            val gain = m.advance(chunk, 1L).main
            assertTrue(abs(gain - previous) <= maxStep, "step ${abs(gain - previous)} exceeds $maxStep at $advanced")
            previous = gain
            advanced += chunk
        }
        assertEquals(expected, previous, 1e-4f)
    }

    @Test
    fun `provisional estimate applies first and full-track value supersedes it`() = withPlayingTrack { m, track ->
        m.normalizationEnabled = true
        m.normalizeForTest(mainGain = 1f)
        m.gainsForTest(1f, 0f)

        m.loudnessForTest(track.identifier, -10f, provisional = true)
        assertEquals(NormalizationGain.fromLufs(-10f, targetLufs = -14f).gain, m.currentTrackGain(), 1e-4f)

        m.loudnessForTest(track.identifier, -8f)
        val full = NormalizationGain.fromLufs(-8f, targetLufs = -14f).gain
        assertEquals(full, m.currentTrackGain(), 1e-4f)

        // A provisional value arriving after the full value is ignored.
        m.loudnessForTest(track.identifier, -20f, provisional = true)
        assertEquals(full, m.currentTrackGain(), 1e-4f)
    }

    @Test
    fun `disabling normalization ramps back to unity`() = withPlayingTrack { m, _ ->
        m.normalizationEnabled = true
        m.normalizeForTest(mainGain = 0.5f)
        m.gainsForTest(1f, 0f)
        m.updateNormalization(false)
        assertEquals(1f, m.currentTrackGain(), 1e-6f)
        val first = m.advance(chunk, 1L).main
        assertTrue(first < 0.52f, "disable must not jump to unity, got $first")
        repeat(rampSamples / chunk + 1) { m.advance(chunk, 1L) }
        assertEquals(1f, m.advance(1, 1L).main, 1e-4f)
    }

    @Test
    fun `unmeasured incoming track inherits the outgoing level`() {
        val m = bareMixer()
        m.normalizationEnabled = true
        m.normalizeForTest(mainGain = 0.5f)
        m.armSubNormForTest(proxyTrack("unknown-${UUID.randomUUID()}"))
        assertEquals(0.5f, m.subNormGainForTest(), 1e-6f)
    }

    @Test
    fun `measured incoming track uses its own gain`() {
        val m = bareMixer()
        m.normalizationEnabled = true
        m.normalizeForTest(mainGain = 0.5f)
        val id = "known-${UUID.randomUUID()}"
        AnalysisCaches.norm[id] = -11f
        m.armSubNormForTest(proxyTrack(id))
        assertEquals(NormalizationGain.fromLufs(-11f, targetLufs = -14f).gain, m.subNormGainForTest(), 1e-4f)
    }

    @Test
    fun `incoming track plays at unity while normalization is disabled`() {
        val m = bareMixer()
        m.normalizeForTest(mainGain = 0.5f)
        m.armSubNormForTest(proxyTrack("off-${UUID.randomUUID()}"))
        assertEquals(1f, m.subNormGainForTest(), 1e-6f)
    }

    private fun bareMixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
    }

    private fun withPlayingTrack(block: (GuildMixer, AudioTrack) -> Unit) {
        val manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(LocalAudioSourceManager())
        manager.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val m = GuildMixer(123L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
        val file = File.createTempFile("mixer-norm-ramp", ".wav")
        try {
            val player = manager.createPlayer()
            m.attachMain(
                Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(dev.arbjerg.lavalink.api.IPlayer::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getAudioPlayer" -> player
                        "getGuildId" -> 123L
                        else -> null
                    }
                } as dev.arbjerg.lavalink.api.IPlayer,
            )
            writeSilenceWav(file)
            val track = loadTrack(manager, file)
            player.playTrack(track)
            block(m, player.playingTrack)
        } finally {
            file.delete()
            m.destroy()
        }
    }

    private fun proxyTrack(id: String): AudioTrack =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioTrack::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "getInfo" -> AudioTrackInfo("t", "a", 1000L, id, false, "uri", null, null)
                "getIdentifier" -> id
                "toString" -> "ProxyTrack($id)"
                "hashCode" -> id.hashCode()
                "equals" -> instance === args?.getOrNull(0)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as AudioTrack

    private fun writeSilenceWav(file: File) {
        val sampleRate = 48000
        val dataLen = sampleRate * 2 * 2
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun intLE(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun shortLE(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.writeBytes("RIFF"); intLE(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); intLE(16); shortLE(1); shortLE(2); intLE(sampleRate); intLE(sampleRate * 4); shortLE(4); shortLE(16)
            out.writeBytes("data"); intLE(dataLen)
            repeat(sampleRate * 2) { shortLE(0) }
        }
    }

    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.complete(playlist.selectedTrack ?: playlist.tracks.first()) }
            override fun noMatches() { future.completeExceptionally(RuntimeException("no match")) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(10, TimeUnit.SECONDS)
    }
}
