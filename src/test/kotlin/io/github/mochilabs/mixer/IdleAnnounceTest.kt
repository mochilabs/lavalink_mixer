package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.ISocketContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Announcements on a guild whose main player has no track. The mixer plays an
 * internal silent carrier so the filter chain runs, and the announcement PCM is
 * mixed into it like into any other main track.
 */
class IdleAnnounceTest {
    private val sampleRate = 48000

    @Test
    fun `sequential announcement is audible on an idle main player and the carrier is cleaned up`() {
        withRig(guildId = 71L) { rig ->
            val file = toneFile("mixer-idle-seq", 440.0, 1)
            try {
                rig.plugin.announce(71L, file.absolutePath, null, 0.2f)
                assertTrue(waitUntil(5_000) { rig.mixer.isIdleCarrierActive() }, "the carrier never started")
                assertNull(rig.mixer.mainTrack(), "the carrier must not look like bot playback")
                assertTrue(waitUntil(5_000) { rig.socket.messagesOfType("announceStart").size == 1 }, "announceStart missing")
                assertTrue(waitUntil(10_000) { rig.socket.messagesOfType("announceEnd").size == 1 }, "announceEnd missing")
                assertTrue(rig.leftPeak.get() > 3000 && rig.rightPeak.get() > 3000, "announcement was not audible (L=${rig.leftPeak.get()} R=${rig.rightPeak.get()})")
                assertTrue(waitUntil(5_000) { rig.player.playingTrack == null }, "the carrier was not cleaned up")
                assertFalse(rig.mixer.isOverlayActive())
                assertEquals(1, rig.events.count { it.startsWith("start:${SilenceAudioTrack.IDENTIFIER}") })
                assertEquals(listOf("end:${SilenceAudioTrack.IDENTIFIER}:STOPPED"), rig.events.filter { it.startsWith("end:") })
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `sequential channel left and right stay on their side`() {
        for ((guild, channel) in listOf(72L to AnnounceChannel.LEFT, 73L to AnnounceChannel.RIGHT)) {
            withRig(guildId = guild) { rig ->
                val file = toneFile("mixer-idle-ch", 440.0, 1)
                try {
                    rig.plugin.announce(guild, file.absolutePath, null, 0.2f, AnnounceMode.SEQUENTIAL, channel)
                    assertTrue(waitUntil(10_000) { rig.socket.messagesOfType("announceEnd").size == 1 }, "announcement did not finish ($channel)")
                    val (loud, quiet) = if (channel == AnnounceChannel.LEFT) rig.leftPeak.get() to rig.rightPeak.get() else rig.rightPeak.get() to rig.leftPeak.get()
                    assertTrue(loud > 3000, "$channel side silent")
                    assertTrue(quiet < 300, "$channel leaked to the other side ($quiet)")
                } finally {
                    file.delete()
                }
            }
        }
    }

    @Test
    fun `parallel announcements are audible on an idle main player`() {
        withRig(guildId = 74L) { rig ->
            val a = toneFile("mixer-idle-par-a", 440.0, 1)
            val b = toneFile("mixer-idle-par-b", 660.0, 1)
            try {
                rig.plugin.announce(74L, a.absolutePath, null, 0.2f, AnnounceMode.PARALLEL, AnnounceChannel.LEFT)
                rig.plugin.announce(74L, b.absolutePath, null, 0.2f, AnnounceMode.PARALLEL, AnnounceChannel.RIGHT)
                assertTrue(waitUntil(15_000) { rig.socket.messagesOfType("announceEnd").size == 2 }, "parallel announcements did not finish")
                assertTrue(rig.leftPeak.get() > 3000 && rig.rightPeak.get() > 3000, "parallel overlays not audible (L=${rig.leftPeak.get()} R=${rig.rightPeak.get()})")
                assertTrue(waitUntil(5_000) { rig.player.playingTrack == null }, "the carrier was not cleaned up")
                assertEquals(0, rig.mixer.parallelOverlayCountForTest())
            } finally {
                a.delete()
                b.delete()
            }
        }
    }

    @Test
    fun `consecutive announcements share one carrier`() {
        withRig(guildId = 75L) { rig ->
            val a = toneFile("mixer-idle-c1", 440.0, 1)
            val b = toneFile("mixer-idle-c2", 660.0, 1)
            try {
                rig.plugin.announce(75L, a.absolutePath, null, 0.2f)
                rig.plugin.announce(75L, b.absolutePath, null, 0.2f)
                assertTrue(
                    waitUntil(20_000) { rig.socket.messagesOfType("announceStart").size == 2 && rig.socket.messagesOfType("announceEnd").size == 2 },
                    "announcements did not play back to back",
                )
                assertTrue(waitUntil(5_000) { rig.player.playingTrack == null }, "the carrier was not cleaned up")
                assertEquals(1, rig.events.count { it.startsWith("start:${SilenceAudioTrack.IDENTIFIER}") }, "carrier restarted between announcements: ${rig.events}")
                assertEquals(1, rig.events.count { it.startsWith("end:${SilenceAudioTrack.IDENTIFIER}") })
            } finally {
                a.delete()
                b.delete()
            }
        }
    }

    @Test
    fun `a bot track replaces the carrier immediately and is left alone afterwards`() {
        withRig(guildId = 76L) { rig ->
            val announcement = toneFile("mixer-idle-long", 440.0, 4)
            val song = toneFile("mixer-idle-song", 220.0, 20)
            try {
                rig.plugin.announce(76L, announcement.absolutePath, null, 0.2f)
                assertTrue(waitUntil(5_000) { rig.mixer.isIdleCarrierActive() }, "the carrier never started")
                val track = load(rig.manager, song)
                rig.player.playTrack(track)
                assertTrue(rig.player.playingTrack === track, "the bot track did not take over")
                assertFalse(rig.mixer.isIdleCarrierActive())
                assertTrue(rig.events.contains("end:${SilenceAudioTrack.IDENTIFIER}:REPLACED"), rig.events.toString())
                // The mixer must neither stop the bot track nor start a second carrier under it.
                assertTrue(waitUntil(10_000) { rig.socket.messagesOfType("announceEnd").size == 1 }, "announcement did not finish over the bot track")
                Thread.sleep(IDLE_WAIT_MS)
                assertTrue(rig.player.playingTrack === track, "the mixer disturbed the bot track")
                assertEquals(1, rig.events.count { it.startsWith("start:${SilenceAudioTrack.IDENTIFIER}") })
                assertTrue(rig.mixer.mainTrack() === track)
            } finally {
                announcement.delete()
                song.delete()
            }
        }
    }

    @Test
    fun `announcing over a playing track never starts a carrier`() {
        withRig(guildId = 77L) { rig ->
            val announcement = toneFile("mixer-idle-over", 440.0, 1)
            val song = toneFile("mixer-idle-over-song", 220.0, 20)
            try {
                val track = load(rig.manager, song)
                rig.player.playTrack(track)
                rig.plugin.announce(77L, announcement.absolutePath, null, 0.2f)
                assertTrue(waitUntil(10_000) { rig.socket.messagesOfType("announceEnd").size == 1 }, "announcement did not finish")
                Thread.sleep(IDLE_WAIT_MS)
                assertTrue(rig.player.playingTrack === track)
                assertTrue(rig.events.none { it.contains(SilenceAudioTrack.IDENTIFIER) }, rig.events.toString())
            } finally {
                announcement.delete()
                song.delete()
            }
        }
    }

    @Test
    fun `announce gain scales the announcement level in sequential and parallel mode`() {
        // Tone peak is 16000: 0.5 -> ~8000, 1.5 -> ~24000 (below the 0.9 soft-limit knee).
        for ((guild, mode, gain, expected) in listOf(
            Quad(78L, AnnounceMode.SEQUENTIAL, 0.5f, 8000),
            Quad(79L, AnnounceMode.SEQUENTIAL, 1.5f, 24000),
            Quad(80L, AnnounceMode.PARALLEL, 0.5f, 8000),
            Quad(81L, AnnounceMode.PARALLEL, 1.5f, 24000),
        )) {
            withRig(guildId = guild) { rig ->
                val file = toneFile("mixer-idle-gain", 440.0, 1)
                try {
                    rig.plugin.announce(guild, file.absolutePath, null, 0.2f, mode, AnnounceChannel.BOTH, gain)
                    assertTrue(waitUntil(10_000) { rig.socket.messagesOfType("announceEnd").size == 1 }, "announcement did not finish ($mode, gain $gain)")
                    val peak = rig.leftPeak.get()
                    assertTrue(peak in (expected * 0.85).toInt()..(expected * 1.15).toInt(), "$mode gain $gain: peak $peak, expected ~$expected")
                } finally {
                    file.delete()
                }
            }
        }
    }

    @Test
    fun `announce gain is bounded and non finite values fall back to unity`() {
        assertEquals(1f, GuildMixer.sanitizeAnnounceGain(Float.NaN))
        assertEquals(0f, GuildMixer.sanitizeAnnounceGain(-3f))
        assertEquals(GuildMixer.MAX_ANNOUNCE_GAIN, GuildMixer.sanitizeAnnounceGain(100f))
        assertEquals(GuildMixer.MAX_ANNOUNCE_GAIN, GuildMixer.sanitizeAnnounceGain(Float.POSITIVE_INFINITY))
        assertEquals(1.7f, GuildMixer.sanitizeAnnounceGain(1.7f))
    }

    private data class Quad(val guild: Long, val mode: AnnounceMode, val gain: Float, val expected: Int)

    @Test
    fun `the carrier can be encoded like any track Lavalink reports to the client`() {
        val manager = DefaultAudioPlayerManager()
        val out = java.io.ByteArrayOutputStream()
        manager.encodeTrack(com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput(out), SilenceAudioTrack())
        assertTrue(out.size() > 0)
        manager.shutdown()
    }

    // ---- harness ----

    private class Rig(
        val manager: DefaultAudioPlayerManager,
        val plugin: MixerPlugin,
        val mixer: GuildMixer,
        val player: AudioPlayer,
        val socket: RecordingSocket,
        val events: CopyOnWriteArrayList<String>,
        val leftPeak: java.util.concurrent.atomic.AtomicInteger,
        val rightPeak: java.util.concurrent.atomic.AtomicInteger,
    )

    private fun withRig(guildId: Long, body: (Rig) -> Unit) {
        val manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        manager.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val plugin = MixerPlugin(manager, MixerConfig())
        val mixer = plugin.getOrCreate(guildId)
        val socket = RecordingSocket()
        val player = manager.createPlayer()
        player.setFilterFactory(PcmFilterFactory { _, _, output -> mutableListOf<AudioFilter>(mixer.newFilter(output)) })
        val events = CopyOnWriteArrayList<String>()
        player.addListener(object : AudioEventAdapter() {
            override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
                events.add("start:${track.identifier}")
            }

            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                events.add("end:${track.identifier}:$endReason")
            }
        })
        mixer.attachMain(iPlayer(guildId, player, socket.context))

        // Stand-in for the Discord voice thread: one frame per 20 ms.
        val left = java.util.concurrent.atomic.AtomicInteger()
        val right = java.util.concurrent.atomic.AtomicInteger()
        val running = AtomicBoolean(true)
        val pump = Thread {
            val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
            while (running.get()) {
                if (player.provide(frame)) {
                    val pcm = ByteBuffer.wrap(frame.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var i = 0
                    while (i + 1 < pcm.limit()) {
                        left.accumulateAndGet(abs(pcm.get(i).toInt())) { a, b -> maxOf(a, b) }
                        right.accumulateAndGet(abs(pcm.get(i + 1).toInt())) { a, b -> maxOf(a, b) }
                        i += 2
                    }
                    Thread.sleep(20)
                } else {
                    Thread.sleep(5)
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            body(Rig(manager, plugin, mixer, player, socket, events, left, right))
        } finally {
            running.set(false)
            pump.join(2_000)
            plugin.destroy()
            player.destroy()
            manager.shutdown()
        }
    }

    private class RecordingSocket {
        val messages = CopyOnWriteArrayList<JsonElement>()

        val context: ISocketContext = Proxy.newProxyInstance(
            RecordingSocket::class.java.classLoader,
            arrayOf(ISocketContext::class.java),
        ) { _, method, args ->
            when (method.name) {
                "sendMessage" -> {
                    messages.add((if (args!!.size == 1) args[0] else args[1]) as JsonElement)
                    null
                }
                "getSessionId" -> "test-session"
                else -> null
            }
        } as ISocketContext

        fun messagesOfType(type: String): List<JsonElement> =
            messages.filter { it.jsonObject["type"]?.jsonPrimitive?.content == type }
    }

    private fun iPlayer(guildId: Long, ap: AudioPlayer, socket: ISocketContext): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAudioPlayer" -> ap
            "getGuildId" -> guildId
            "getTrack" -> ap.playingTrack
            "isPlaying" -> ap.playingTrack != null
            "getSocketContext" -> socket
            "play" -> {
                ap.playTrack(args!![0] as AudioTrack)
                null
            }
            "stop" -> {
                ap.stopTrack()
                null
            }
            else -> null
        }
    } as IPlayer

    private fun load(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.complete(playlist.tracks.first()) }
            override fun noMatches() { future.completeExceptionally(NoMatchException(file.absolutePath)) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun toneFile(prefix: String, frequency: Double, seconds: Int): File {
        val file = File.createTempFile(prefix, ".wav")
        val samples = sampleRate * seconds
        val dataLen = samples * 2 * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF"); out.writeIntLE(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLE(16); out.writeShortLE(1); out.writeShortLE(2)
            out.writeIntLE(sampleRate); out.writeIntLE(sampleRate * 2 * 2); out.writeShortLE(4); out.writeShortLE(16)
            out.writeBytes("data"); out.writeIntLE(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2.0 * PI * frequency * i / sampleRate) * 16000).toInt()
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF); write((value shr 16) and 0xFF); write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }

    private companion object {
        /** Long enough for a wrongly restarted carrier or a wrongly stopped bot track to show up. */
        const val IDLE_WAIT_MS = GuildMixer.IDLE_CARRIER_GRACE_MS + 800
    }
}
