package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.ISocketContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A destroyed mixer must refuse to create players. Announcement resolution and
 * overlap preparation finish asynchronously, and before the destroyed flag they
 * could create an AudioPlayer on a mixer nobody would drain or destroy again.
 */
class MixerLifecycleTest {
    private class CountingManager : DefaultAudioPlayerManager() {
        val created = AtomicInteger()
        override fun createPlayer(): AudioPlayer {
            created.incrementAndGet()
            return super.createPlayer()
        }
    }

    private fun manager() = CountingManager().apply {
        registerSourceManager(LocalAudioSourceManager())
        configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
    }

    /** Never decoded: the destroyed checks run before the track is touched. */
    private fun fakeTrack(): AudioTrack = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(AudioTrack::class.java),
    ) { _, method, _ -> error("track must not be used after destroy (${method.name})") } as AudioTrack

    @Test
    fun `sequential overlay start after destroy creates no player`() {
        val manager = manager()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mixer = GuildMixer(1L, manager, MixerConfig(), executor)
            mixer.destroy()
            val before = manager.created.get()
            assertFailsWith<MixerDestroyedException> {
                mixer.startOverlay(fakeTrack(), 0.3f, 1L, AnnounceMode.SEQUENTIAL)
            }
            assertEquals(before, manager.created.get(), "a destroyed mixer created a secondary player")
        } finally {
            executor.shutdownNow()
            manager.shutdown()
        }
    }

    @Test
    fun `parallel overlay start after destroy creates no player`() {
        val manager = manager()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mixer = GuildMixer(2L, manager, MixerConfig(), executor)
            mixer.destroy()
            val before = manager.created.get()
            assertFailsWith<MixerDestroyedException> {
                mixer.startOverlay(fakeTrack(), 0.3f, 1L, AnnounceMode.PARALLEL)
            }
            assertEquals(before, manager.created.get(), "a destroyed mixer created a parallel overlay player")
        } finally {
            executor.shutdownNow()
            manager.shutdown()
        }
    }

    @Test
    fun `analysis scan running at destroy ends aborted and nothing creates a new analysis player`() {
        val manager = manager()
        val file = toneFile("mixer-lifecycle-scan", seconds = 30)
        val service = TrackAnalysisService(manager, 4L)
        try {
            val track = loadTrack(manager, file)
            val destroyedMidScan = AtomicBoolean(false)
            // The provisional estimate fires on the scan thread after 8 s of decoded audio,
            // so destroying there lands deterministically inside the running scan.
            val loudness = service.measureBaseline(track) {
                if (destroyedMidScan.compareAndSet(false, true)) service.destroy()
            }
            assertTrue(destroyedMidScan.get(), "the scan never reached the provisional estimate")
            assertNull(loudness, "a scan cut short by destroy must not report a loudness to cache")

            val created = manager.created.get()
            assertFailsWith<MixerDestroyedException> { service.measureBaseline(track.makeClone()) }
            service.beginScan()
            assertFailsWith<MixerDestroyedException> { service.measureBaseline(track.makeClone()) }
            assertEquals(created, manager.created.get(), "a destroyed analysis service created a new player")
        } finally {
            service.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `destroy racing between getOrCreate and attachMain replaces the destroyed mixer with a live one`() {
        val manager = manager()
        val plugin = MixerPlugin(manager, MixerConfig())
        try {
            val events = MixerEvents(plugin)
            val socket = proxySocket()
            val guildId = 10L
            val oldPlayer = proxyPlayer(guildId, manager.createPlayer())
            val newPlayer = proxyPlayer(guildId, manager.createPlayer())

            events.onNewPlayer(socket, oldPlayer)
            val oldMixer = assertNotNull(plugin.find(guildId))
            assertTrue(oldMixer.isMain(oldPlayer))

            val hookRan = AtomicBoolean(false)
            plugin.beforeAttachForTest = {
                if (hookRan.compareAndSet(false, true)) {
                    events.onDestroyPlayer(socket, oldPlayer)
                }
            }
            events.onNewPlayer(socket, newPlayer)
            plugin.beforeAttachForTest = null

            assertTrue(hookRan.get(), "beforeAttachForTest hook did not run")
            assertTrue(oldMixer.isDestroyedForTest(), "old mixer must be destroyed when oldPlayer is destroyed")
            assertFalse(oldMixer.isMain(newPlayer), "destroyed old mixer must not accept newPlayer")

            val liveMixer = assertNotNull(plugin.find(guildId), "registry must keep a live mixer for newPlayer")
            assertNotSame(oldMixer, liveMixer)
            assertFalse(liveMixer.isDestroyedForTest(), "replacement mixer must be live")
            assertTrue(liveMixer.isMain(newPlayer), "replacement mixer must be attached to newPlayer")
        } finally {
            plugin.destroy()
            manager.shutdown()
        }
    }

    @Test
    fun `destroy of old player after new player attached keeps the mixer attached to new player`() {
        val manager = manager()
        val plugin = MixerPlugin(manager, MixerConfig())
        try {
            val events = MixerEvents(plugin)
            val socket = proxySocket()
            val guildId = 11L
            val oldPlayer = proxyPlayer(guildId, manager.createPlayer())
            val newPlayer = proxyPlayer(guildId, manager.createPlayer())

            events.onNewPlayer(socket, oldPlayer)
            val mixer = assertNotNull(plugin.find(guildId))
            events.onNewPlayer(socket, newPlayer)
            assertSame(mixer, plugin.find(guildId))
            assertTrue(mixer.isMain(newPlayer))

            events.onDestroyPlayer(socket, oldPlayer)
            val surviving = assertNotNull(plugin.find(guildId), "destroying oldPlayer must not remove newPlayer's mixer")
            assertSame(mixer, surviving)
            assertFalse(surviving.isDestroyedForTest(), "destroying oldPlayer must not destroy newPlayer's mixer")
            assertTrue(surviving.isMain(newPlayer))
        } finally {
            plugin.destroy()
            manager.shutdown()
        }
    }

    @Test
    fun `plugin destroy refuses getOrCreate and cleans up racing mixer creation`() {
        val manager = manager()
        val plugin = MixerPlugin(manager, MixerConfig())
        try {
            val existing = plugin.getOrCreate(10L)
            var racingMixer: GuildMixer? = null
            plugin.afterCreateForTest = {
                racingMixer = plugin.find(11L)
                plugin.destroy()
            }

            assertFailsWith<MixerDestroyedException> {
                plugin.getOrCreate(11L)
            }
            assertTrue(existing.isDestroyedForTest(), "existing mixer must be destroyed by plugin.destroy()")
            assertNotNull(racingMixer, "computeIfAbsent should have created the racing mixer before the hook")
            assertTrue(racingMixer!!.isDestroyedForTest(), "racing mixer created during shutdown must be destroyed")
            assertNull(plugin.find(10L))
            assertNull(plugin.find(11L))

            plugin.afterCreateForTest = null
            assertFailsWith<MixerDestroyedException> {
                plugin.getOrCreate(12L)
            }
            assertNull(plugin.find(12L))
        } finally {
            plugin.destroy()
            manager.shutdown()
        }
    }

    private fun proxySocket(): ISocketContext = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(ISocketContext::class.java),
    ) { _, _, _ -> null } as ISocketContext

    private fun proxyPlayer(guildId: Long, ap: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAudioPlayer" -> ap
            "getGuildId" -> guildId
            else -> null
        }
    } as IPlayer


    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.complete(playlist.tracks.first()) }
            override fun noMatches() { future.completeExceptionally(IllegalStateException("no match for $file")) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun toneFile(prefix: String, seconds: Int): File {
        val sampleRate = 48000
        val file = File.createTempFile(prefix, ".wav")
        val samples = sampleRate * seconds
        val dataLen = samples * 2 * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); le32(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(sampleRate); le32(sampleRate * 4); le16(4); le16(16)
            out.writeBytes("data"); le32(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2 * PI * 440.0 * i / sampleRate) * 0.3 * Short.MAX_VALUE).toInt()
                le16(v); le16(v)
            }
        }
        return file
    }
}
