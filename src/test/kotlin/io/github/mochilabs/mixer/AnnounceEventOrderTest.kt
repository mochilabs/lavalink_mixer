package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.ISocketContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `announceEnd` follows `announceStart` for the same overlay and is never sent
 * on its own. Each test ends the overlay's track at a fixed point of the start
 * sequence through a test seam, so the interleaving is deterministic.
 */
class AnnounceEventOrderTest {
    private val sampleRate = 48000

    @Test
    fun `sequential overlay ending while its start is sent reports start then end`() =
        endingWhileStartIsSent(AnnounceMode.SEQUENTIAL, guildId = 71L)

    @Test
    fun `parallel overlay ending while its start is sent reports start then end`() =
        endingWhileStartIsSent(AnnounceMode.PARALLEL, guildId = 72L)

    @Test
    fun `sequential overlay ending before its start is confirmed sends no events`() =
        endingBeforeStartIsConfirmed(AnnounceMode.SEQUENTIAL, guildId = 73L)

    @Test
    fun `parallel overlay ending before its start is confirmed sends no events`() =
        endingBeforeStartIsConfirmed(AnnounceMode.PARALLEL, guildId = 74L)

    private fun endingWhileStartIsSent(mode: AnnounceMode, guildId: Long) = withAnnouncement(mode, guildId) { file, mixer, socket, plugin ->
        val fired = AtomicBoolean(false)
        mixer.overlaysForTest.beforeAnnounceStartForTest = { player ->
            // stopTrack dispatches onTrackEnd on this thread, which finishes the overlay.
            if (fired.compareAndSet(false, true)) player.stopTrack()
        }
        plugin.announce(guildId, file.absolutePath, null, 0.2f, mode)
        assertTrue(waitUntil(5_000) { socket.announceTypes().size >= 2 }, "events: ${socket.announceTypes()}")
        assertTrue(fired.get(), "the test seam never ran")
        assertEquals(listOf("announceStart", "announceEnd"), socket.announceTypes())
    }

    private fun endingBeforeStartIsConfirmed(mode: AnnounceMode, guildId: Long) = withAnnouncement(mode, guildId) { file, mixer, socket, plugin ->
        val fired = AtomicBoolean(false)
        mixer.overlaysForTest.afterOverlayPlayForTest = { player ->
            if (fired.compareAndSet(false, true)) player.stopTrack()
        }
        plugin.announce(guildId, file.absolutePath, null, 0.2f, mode)
        assertTrue(waitUntil(5_000) { fired.get() }, "the test seam never ran")
        assertTrue(
            waitUntil(5_000) { !mixer.isOverlayActive() && mixer.parallelOverlayCountForTest() == 0 && !mixer.isSubBusy() },
            "the aborted overlay was not torn down",
        )
        Thread.sleep(200)
        assertEquals(emptyList(), socket.announceTypes(), "an overlay that never started must send no announce events")
    }

    private fun withAnnouncement(
        mode: AnnounceMode,
        guildId: Long,
        body: (File, GuildMixer, RecordingSocket, MixerPlugin) -> Unit,
    ) {
        val manager = manager()
        val file = toneFile("mixer-announce-order-${mode.name.lowercase()}", 440.0, 3)
        val plugin = MixerPlugin(manager, MixerConfig())
        try {
            val socket = RecordingSocket()
            val mixer = plugin.getOrCreate(guildId)
            mixer.attachMain(iPlayer(guildId, manager.createPlayer(), socket.context))
            body(file, mixer, socket, plugin)
        } finally {
            plugin.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    // ---- harness ----

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

        fun announceTypes(): List<String> =
            messages.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.content }.filter { it.startsWith("announce") }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        return m
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
