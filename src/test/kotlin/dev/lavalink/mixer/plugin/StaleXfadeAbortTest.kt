package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards against zombie overlaps: a crossfade armed on the outgoing track must
 * not survive the client replacing/stopping that track. Before the teardown
 * hooks existed, `onTrackEnd` ignored REPLACED/STOPPED and the armed handoff
 * stayed live in the old track's coordinates — minutes later the replacement
 * track would cross the stale handoff position and get swapped for the
 * fading-in sub track mid-play ("a different song suddenly starts").
 */
class StaleXfadeAbortTest {
    private val sampleRate = 48000
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    /** Records the plugin's custom `{op: "mixer"}` websocket pushes. */
    private class RecordingSocket {
        val messages = CopyOnWriteArrayList<JsonElement>()

        val context: ISocketContext = Proxy.newProxyInstance(
            RecordingSocket::class.java.classLoader,
            arrayOf(ISocketContext::class.java),
        ) { _, method, args ->
            when (method.name) {
                "sendMessage" -> {
                    // Overloads: (SerializationStrategy, value) and (JsonElement).
                    val payload = if (args!!.size == 1) args[0] else args[1]
                    messages.add(payload as JsonElement)
                    null
                }
                "getSessionId" -> "test-session"
                else -> null
            }
        } as ISocketContext

        fun messagesOfType(type: String): List<JsonElement> =
            messages.filter { it.jsonObject["type"]?.jsonPrimitive?.content == type }
    }

    @Test
    fun `a client replace during the overlap aborts the crossfade synchronously`() {
        val manager = manager()
        val outgoing = toneFile("mixer-stale-out", 440.0, 10)
        val incoming = toneFile("mixer-stale-in", 880.0, 10)
        val replacement = toneFile("mixer-stale-replacement", 520.0, 12)
        try {
            val config = MixerConfig().apply {
                crossfadeEnabled = true
                crossfadeMs = 3000
                fadeInMs = 0
                maskMs = 0
                silenceSkipEnabled = false
            }
            val mixer = GuildMixer(21L, manager, config)
            val player = manager.createPlayer()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf(mixer.newFilter(output))
            })
            val socket = RecordingSocket()
            mixer.attachMain(iPlayer(21L, player, socket.context))

            val trackA = loadTrack(manager, outgoing)
            val trackB = loadTrack(manager, incoming)
            val trackC = loadTrack(manager, replacement)
            player.playTrack(trackA)
            mixer.queueNext(CompletableFuture.completedFuture(trackB))

            pumpUntil(player, mixer) { mixer.isXfadeActive() }
            assertTrue(mixer.isXfadeActive(), "the overlap never armed")

            // The client is told what is fading in, so UIs can show a transition.
            val starts = socket.messagesOfType("overlapStart")
            assertTrue(starts.isNotEmpty(), "no overlapStart event reached the client")
            assertEquals(
                trackB.identifier,
                starts.last().jsonObject["track"]?.jsonObject?.get("identifier")?.jsonPrimitive?.content,
                "overlapStart named the wrong incoming track",
            )

            // What a skip/stop/play from the client does: replace the main track
            // while the fade is running.
            player.playTrack(trackC)

            assertFalse(
                mixer.isXfadeActive(),
                "the armed crossfade survived the client replace (zombie arm)",
            )
            val ends = socket.messagesOfType("overlapEnd")
            assertTrue(ends.isNotEmpty(), "no overlapEnd event reached the client")
            assertEquals("aborted", ends.last().jsonObject["reason"]?.jsonPrimitive?.content)

            // The replacement must play to its own end — across the replaced
            // track's stale handoff position (10s) — without being swapped for B.
            val hijacker = pumpWhilePlaying(player, mixer, trackC, maxMs = 14_000)
            assertEquals(null, hijacker, "the queued sub track replaced the client's track mid-play")
        } finally {
            outgoing.delete()
            incoming.delete()
            replacement.delete()
        }
    }

    @Test
    fun `an armed handoff only fires for the main track it belongs to`() {
        val manager = manager()
        val fileA = toneFile("mixer-guard-a", 440.0, 10)
        val fileC = toneFile("mixer-guard-c", 520.0, 10)
        try {
            val mixer = GuildMixer(22L, manager, MixerConfig().apply { fadeInMs = 0; silenceSkipEnabled = false })
            val player = manager.createPlayer()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf(mixer.newFilter(output))
            })
            mixer.attachMain(iPlayer(22L, player))

            val trackC = loadTrack(manager, fileC)
            val other = loadTrack(manager, fileA)
            player.playTrack(trackC)
            pumpFrames(player, mixer, targetMs = 1_000)

            // Identity mismatch: the armed state belongs to another track.
            mixer.armHandoffForTest(other, handoffMs = 500)
            mixer.poll()
            assertFalse(mixer.isXfadeActive(), "a handoff armed for a replaced track was not torn down")
            assertTrue(player.playingTrack === trackC, "the playing track was disturbed by a stale handoff")

            // Identity match: the handoff fires and disarms cleanly. No queued
            // sub track exists here, so the transition is a gains reset only.
            mixer.armHandoffForTest(trackC, handoffMs = 500)
            mixer.poll()
            assertFalse(mixer.isXfadeActive(), "a valid handoff left the arm state behind")
            assertTrue(player.playingTrack === trackC, "a valid handoff must not replace the main track on its own")
            player.stopTrack()
            mixer.destroy()
        } finally {
            fileA.delete()
            fileC.delete()
        }
    }

    // ---- harness (same pattern as CrossfadeMixIntegrationTest) ----

    /**
     * Pumps [expected] to its natural end, returning the identifier of any
     * other track that showed up as the main playing track (null when the
     * replacement played undisturbed).
     */
    private fun pumpWhilePlaying(player: AudioPlayer, mixer: GuildMixer, expected: AudioTrack, maxMs: Long): String? {
        var hijacker: String? = null
        val deadline = System.currentTimeMillis() + 60_000
        var frames = 0L
        val started = System.nanoTime()
        val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
        while (System.currentTimeMillis() < deadline) {
            val playing = player.playingTrack
            if (playing != null && playing !== expected && playing.identifier != expected.identifier) {
                hijacker = playing.identifier
                return hijacker
            }
            if (playing == null && frames > 0) return null
            if (frames * 20 >= maxMs) return null
            if (player.provide(frame)) {
                frames++
                mixer.drain()
                if (frames % 5L == 0L) mixer.poll()
                pace(started, frames)
            } else {
                mixer.poll()
                Thread.sleep(1)
            }
        }
        return hijacker
    }

    private fun pumpUntil(player: AudioPlayer, mixer: GuildMixer, stop: () -> Boolean) {
        val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.nanoTime()
        var frames = 0L
        while (System.currentTimeMillis() < deadline) {
            if (player.provide(frame)) {
                frames++
                mixer.drain()
                if (frames % 5L == 0L) mixer.poll()
                pace(started, frames)
            } else {
                mixer.poll()
                Thread.sleep(1)
            }
            if (stop()) return
        }
    }

    private fun pumpFrames(player: AudioPlayer, mixer: GuildMixer, targetMs: Long) {
        val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.nanoTime()
        var frames = 0L
        val targetFrames = targetMs / 20L
        while (frames < targetFrames && System.currentTimeMillis() < deadline) {
            if (player.provide(frame)) {
                frames++
                mixer.drain()
                if (frames % 5L == 0L) mixer.poll()
                pace(started, frames)
            } else {
                mixer.poll()
                Thread.sleep(1)
            }
        }
        assertTrue(frames >= targetFrames, "only decoded ${frames * 20}ms of ${targetMs}ms")
    }

    private fun pace(started: Long, frames: Long) {
        val due = started + frames * 20_000_000L
        val remaining = due - System.nanoTime()
        if (remaining <= 0L) return
        try {
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = format
        return m
    }

    private fun iPlayer(guildId: Long, ap: AudioPlayer, socket: ISocketContext? = null): IPlayer = Proxy.newProxyInstance(
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

    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                future.complete(playlist.selectedTrack ?: playlist.tracks.first())
            }

            override fun noMatches() {
                future.completeExceptionally(NoMatchException(file.absolutePath))
            }

            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun toneFile(prefix: String, frequency: Double, seconds: Int): File {
        val file = File.createTempFile(prefix, ".wav")
        val samples = sampleRate * seconds
        val dataLen = samples * 2 * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(2)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2 * 2)
            out.writeShortLE(4)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2.0 * PI * frequency * i / sampleRate) * 16000).toInt()
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
