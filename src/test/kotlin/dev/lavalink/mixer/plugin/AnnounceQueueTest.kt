package dev.lavalink.mixer.plugin

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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Announcements (TTS/overlays) queue and play back to back. There is a single
 * secondary decoder, so back-to-back talk-over used to 409 or drop; the queue
 * holds them and the mixer loop starts each one as the sub player frees up,
 * holding them through overlap windows instead of losing them.
 */
class AnnounceQueueTest {
    private val sampleRate = 48000

    @Test
    fun `queued announcements play back to back automatically`() {
        val manager = manager()
        val fileA = toneFile("mixer-announce-a", 440.0, 1)
        val fileB = toneFile("mixer-announce-b", 660.0, 1)
        try {
            val plugin = MixerPlugin(manager, MixerConfig())
            val guildId = 41L
            val socket = RecordingSocket()
            val player = manager.createPlayer()
            plugin.getOrCreate(guildId).attachMain(iPlayer(guildId, player, socket.context))

            val (id1, position1) = plugin.announce(guildId, fileA.absolutePath, null, 0.2f)
            val (id2, position2) = plugin.announce(guildId, fileB.absolutePath, null, 0.2f)
            assertEquals(1, position1, "first announcement should be first in line")
            assertEquals(2, position2, "second announcement should wait behind the first")

            val mixer = plugin.find(guildId)!!
            try {
                assertTrue(waitUntil(5_000) { mixer.isOverlayActive() }, "the first announcement never started")
                assertEquals(1, mixer.announcementQueueSize(), "the second announcement must wait for the sub player")

                assertTrue(
                    waitUntil(15_000) {
                        socket.messagesOfType("announceStart").size == 2 && socket.messagesOfType("announceEnd").size == 2
                    },
                    "announcements did not play back to back " +
                        "(starts=${socket.messagesOfType("announceStart").size}, " +
                        "ends=${socket.messagesOfType("announceEnd").size})",
                )
                assertEquals(0, mixer.announcementQueueSize(), "the queue should be empty after both played")
                val started = socket.messagesOfType("announceStart")
                assertEquals(
                    id1.toString(),
                    started[0].jsonObject["announcementId"]?.jsonPrimitive?.content,
                    "announcements played out of queue order",
                )
                assertEquals(id2.toString(), started[1].jsonObject["announcementId"]?.jsonPrimitive?.content)
            } finally {
                plugin.destroy()
            }
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun `a busy secondary player holds announcements instead of dropping them`() {
        val mixer = GuildMixer(42L, manager(), MixerConfig())
        val (id, position) = mixer.enqueueAnnouncement("x", null, 0.2f)
        assertEquals(1, position)
        assertEquals(1, mixer.announcementQueueSize())

        // Overlap armed: nothing may start on the sub player.
        mixer.beginXfadeForTest(100)
        assertNull(mixer.takeNextAnnouncement(), "an announcement started during an overlap")
        mixer.endXfadeForTest()

        val item = mixer.takeNextAnnouncement()
        assertEquals(id, item?.id)
        assertNull(mixer.takeNextAnnouncement(), "a second start must be blocked while one is resolving")

        // Busy race during resolution: the item returns to the head, not to limbo.
        mixer.requeueAnnouncement(item!!, null)
        assertEquals(id, mixer.takeNextAnnouncement()?.id, "a re-queued announcement must keep its place")

        mixer.onAnnouncementStarted(item)
        assertEquals(0, mixer.announcementQueueSize())

        val (otherId, otherPosition) = mixer.enqueueAnnouncement("y", null, 0.2f)
        assertEquals(1, otherPosition)
        assertEquals(1, mixer.clearAnnouncements())
        assertNull(mixer.takeNextAnnouncement())
        assertEquals(0, mixer.announcementQueueSize())
        assertTrue(otherId > id)
    }

    @Test
    fun `parallel announcements start despite a busy sequential slot and mix into output`() {
        val manager = manager()
        val fileA = toneFile("mixer-par-a", 440.0, 1)
        val fileB = toneFile("mixer-par-b", 660.0, 1)
        try {
            val plugin = MixerPlugin(manager, MixerConfig())
            val guildId = 51L
            val socket = RecordingSocket()
            val player = manager.createPlayer()
            plugin.getOrCreate(guildId).attachMain(iPlayer(guildId, player, socket.context))

            // Two parallel announcements: both may start concurrently.
            plugin.announce(guildId, fileA.absolutePath, null, 0.2f, GuildMixer.AnnounceMode.PARALLEL)
            plugin.announce(guildId, fileB.absolutePath, null, 0.2f, GuildMixer.AnnounceMode.PARALLEL)

            val mixer = plugin.find(guildId)!!
            try {
                // Both parallel overlays started (two announceStart events).
                assertTrue(
                    waitUntil(5_000) { socket.messagesOfType("announceStart").size == 2 },
                    "parallel announcements did not both start " +
                        "(starts=${socket.messagesOfType("announceStart").size})",
                )
                assertEquals(2, mixer.parallelOverlayCountForTest(), "both parallel slots should be active")
            } finally {
                plugin.destroy()
            }
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun `parallel mode respects the max overlay limit`() {
        val mixer = GuildMixer(52L, manager(), MixerConfig().apply { maxParallelOverlays = 2 })
        mixer.enqueueAnnouncement("a", null, 0.2f, GuildMixer.AnnounceMode.PARALLEL)
        mixer.enqueueAnnouncement("b", null, 0.2f, GuildMixer.AnnounceMode.PARALLEL)
        mixer.enqueueAnnouncement("c", null, 0.2f, GuildMixer.AnnounceMode.PARALLEL)

        // Two parallel slots fit; the third waits (announcementResolving gates a single pop at a time).
        val first = mixer.takeNextAnnouncement()
        assertEquals(GuildMixer.AnnounceMode.PARALLEL, first?.mode)
        mixer.onAnnouncementStarted(first!!)
        val second = mixer.takeNextAnnouncement()
        assertEquals(GuildMixer.AnnounceMode.PARALLEL, second?.mode)
        mixer.onAnnouncementStarted(second!!)
        // Once the two slots are "active" (simulated), the third cannot pop.
        mixer.armParallelForTest(2)
        assertNull(mixer.takeNextAnnouncement(), "a third parallel overlay exceeded the limit")
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
