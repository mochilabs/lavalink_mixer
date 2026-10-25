package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import dev.arbjerg.lavalink.api.IPlayer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards the voice-thread feed arbitration. Two filter chains resolve to one
 * [GuildMixer] when two players exist for a guild (a resuming session racing
 * a fresh restore). Unchecked, every ~20ms chunk from both chains advanced
 * chainPos — the counter ran at exactly 2x real playback, armed crossfades
 * half a song early, and both chains drained the shared sub siphon dry
 * ("crossfade sub starvation" with a perfectly healthy decoder).
 */
class FeederArbitrationTest {
    private fun mixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
    }

    /** One 20ms stereo chunk at 48kHz. */
    private fun chunk(): Array<FloatArray> = Array(2) { FloatArray(960) }

    @Test
    fun `a rival filter that takes over while the incumbent is live must not advance the chain`() {
        val m = mixer()
        val incumbent = MixerFilter(m, null)
        val rival = MixerFilter(m, null)
        repeat(50) { incumbent.process(chunk(), 0, 960) }
        assertEquals(50 * 960L, m.chainPosSamples())
        repeat(50) { rival.process(chunk(), 0, 960) }
        assertEquals(50 * 960L, m.chainPosSamples(), "rival filter advanced the chain while the incumbent was live")
    }

    @Test
    fun `two live filters feeding concurrently count each chunk once`() {
        val m = mixer()
        val first = MixerFilter(m, null)
        val rival = MixerFilter(m, null)
        repeat(50) {
            first.process(chunk(), 0, 960)
            rival.process(chunk(), 0, 960)
        }
        assertEquals(50 * 960L, m.chainPosSamples())
    }

    @Test
    fun `closing the incumbent hands the feed over without a gap`() {
        val m = mixer()
        val incumbent = MixerFilter(m, null)
        val rival = MixerFilter(m, null)
        repeat(10) { incumbent.process(chunk(), 0, 960) }
        incumbent.close()
        rival.process(chunk(), 0, 960)
        assertEquals(11 * 960L, m.chainPosSamples())
    }

    @Test
    fun `a silent incumbent is taken over after the stale budget`() {
        val m = mixer()
        m.feederStaleMs = 30L
        val incumbent = MixerFilter(m, null)
        val rival = MixerFilter(m, null)
        repeat(10) { incumbent.process(chunk(), 0, 960) }
        Thread.sleep(60)
        rival.process(chunk(), 0, 960)
        assertEquals(11 * 960L, m.chainPosSamples())
        // The rival owns the feed now; the old incumbent must not count.
        incumbent.process(chunk(), 0, 960)
        assertEquals(11 * 960L, m.chainPosSamples(), "old incumbent kept counting after takeover")
    }

    @Test
    fun `destroying a non-main player keeps the mixer alive`() {
        val manager = DefaultAudioPlayerManager()
        val plugin = MixerPlugin(manager, MixerConfig())
        try {
            val events = MixerEvents(plugin)
            val socket = proxySocket()
            val stalePlayer = proxyPlayer(77L, manager.createPlayer())
            val livePlayer = proxyPlayer(77L, manager.createPlayer())
            events.onNewPlayer(socket, stalePlayer)
            events.onNewPlayer(socket, livePlayer)
            // The stale session's player dies while the fresh one is live.
            events.onDestroyPlayer(socket, stalePlayer)
            assertNotNull(plugin.find(77L), "destroying a non-main player removed the mixer")
            // Main player teardown tears the mixer down with it.
            events.onDestroyPlayer(socket, livePlayer)
            assertNull(plugin.find(77L))
        } finally {
            plugin.destroy()
        }
    }

    private fun proxySocket(): dev.arbjerg.lavalink.api.ISocketContext = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(dev.arbjerg.lavalink.api.ISocketContext::class.java),
    ) { _, _, _ -> null } as dev.arbjerg.lavalink.api.ISocketContext

    private fun proxyPlayer(guildId: Long, ap: com.sedmelluq.discord.lavaplayer.player.AudioPlayer): IPlayer =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(IPlayer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAudioPlayer" -> ap
                "getGuildId" -> guildId
                else -> null
            }
        } as IPlayer
}
