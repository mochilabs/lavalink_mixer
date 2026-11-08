package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import com.sun.net.httpserver.HttpServer
import dev.arbjerg.lavalink.api.IPlayer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Records what the player actually *provides* (what Lavalink would send to
 * Discord), not what the decode-side filter chain sees, and serves the
 * incoming track over a deliberately slow HTTP server.
 *
 * A crossfade handoff starts a clone of the incoming track in the main player.
 * Opening and seeking that clone takes as long as the source needs to answer;
 * the mixed audio that was still buffered must cover that time. The local
 * file tests cannot see this because a local file opens in microseconds.
 *
 * The incoming track's right channel is a 1 kHz carrier whose amplitude rises
 * linearly through the file, so every 20 ms output frame carries the source
 * position it came from. The outgoing right channel is silent. After the fade
 * the right channel is the incoming track alone: a gap reads as zero
 * amplitude, a skip or a repeat as a jump in the ramp.
 */
class HandoffContinuityTest {
    private val sampleRate = 48_000
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE
    private val incomingSeconds = 12
    private val ampLow = 0.1
    private val ampHigh = 0.9
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanup.asReversed().forEach { runCatching(it) }
    }

    @Test
    fun `mid-track handoff from a slow source has no output gap`() {
        assertSeamless(responseDelayMs = 600L)
    }

    @Test
    fun `mid-track handoff from a very slow source has no output gap`() {
        assertSeamless(responseDelayMs = 3_000L)
    }

    @Test
    fun `handoff at the outgoing file end leaves audio to cover a slow source`() {
        // No requested end: the crossfade would run to the physical end of
        // the outgoing file, where its decoder stops and nothing is buffered.
        assertSeamless(responseDelayMs = 1_000L, outgoingEndMs = null)
    }

    private fun assertSeamless(responseDelayMs: Long, outgoingEndMs: Long? = 6_000L) {
        val outgoingFile = wavFile("handoff-out", seconds = if (outgoingEndMs == null) 8 else 12) { i, _ -> sin(2 * PI * 440.0 * i / sampleRate) * 0.5 to 0.0 }
        val incomingBytes = wavBytes(incomingSeconds) { i, n ->
            val amp = ampLow + (ampHigh - ampLow) * i / n
            sin(2 * PI * 880.0 * i / sampleRate) * 0.5 to sin(2 * PI * 1_000.0 * i / sampleRate) * amp
        }
        // Only the handoff clone's requests are slow: the overlap itself
        // must start (its prefill has its own deadline), and the clone's
        // open/seek latency is what the handoff has to cover.
        val slow = java.util.concurrent.atomic.AtomicBoolean(false)
        val url = slowServer(incomingBytes) { if (slow.get()) responseDelayMs else 0L }

        val manager = DefaultAudioPlayerManager().apply {
            registerSourceManager(LocalAudioSourceManager())
            registerSourceManager(HttpAudioSourceManager())
            configuration.outputFormat = format
        }
        cleanup += { manager.shutdown() }
        val mixer = GuildMixer(21L, manager, MixerConfig().apply {
            crossfadeEnabled = true
            crossfadeMs = 2_000
            fadeInMs = 0
            maskMs = 80
            silenceSkipEnabled = false
        }, java.util.concurrent.Executor { it.run() })
        cleanup += { mixer.destroy() }
        val player = manager.createPlayer()
        cleanup += { player.destroy() }
        player.setFilterFactory(PcmFilterFactory { _, _, output -> mutableListOf(mixer.newFilter(output)) })
        mixer.attachMain(iPlayer(21L, player))

        val trackA = loadTrack(manager, outgoingFile.absolutePath)
        val trackB = loadTrack(manager, url)
        player.playTrack(trackA)
        if (outgoingEndMs != null) {
            mixer.reset(1L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
            // A handoff well before the outgoing file ends: the outgoing decoder
            // is still running, so seconds of mixed incoming audio are buffered.
            assertIs<ArmResult.Success>(
                mixer.arm(1L, UUID.randomUUID().toString(), trackB, false, outgoingEndPositionMs = outgoingEndMs),
            )
        } else {
            mixer.queueNext(CompletableFuture.completedFuture(trackB))
        }

        val amps = ArrayList<Double>()
        var handoffFrame = -1
        pump(player, mixer, amps) {
            if (mixer.isXfadeActive()) slow.set(true)
            if (handoffFrame < 0 && player.playingTrack != null && player.playingTrack !== trackA) handoffFrame = amps.size
            handoffFrame >= 0 && amps.size - handoffFrame > (responseDelayMs + 2_000L) / 20
        }
        assertTrue(slow.get(), "the overlap never started")
        assertTrue(handoffFrame >= 0, "the crossfade never handed off (recorded ${amps.size * 20}ms)")

        // The fade has finished by the handoff, so from here on the right
        // channel is the incoming ramp at unity gain.
        val expectedStep = (ampHigh - ampLow) * 0.02 / incomingSeconds
        val problems = mutableListOf<String>()
        var silentRun = 0
        var longestSilentRun = 0
        for (i in handoffFrame until amps.size) {
            if (amps[i] < ampLow * 0.5) {
                silentRun++
                longestSilentRun = maxOf(longestSilentRun, silentRun)
                continue
            }
            silentRun = 0
            val prev = amps[i - 1]
            if (prev < ampLow * 0.5) continue
            val step = amps[i] - prev
            // Allow one frame of skip or repeat at the seam; more is audible.
            if (step < -expectedStep || step > 3 * expectedStep) {
                problems += "frame ${i - handoffFrame}: ramp jumped by %.1f frames".format(step / expectedStep - 1)
            }
        }
        assertTrue(
            longestSilentRun <= 1,
            "output went silent for ${longestSilentRun * 20}ms after the handoff (source delay ${responseDelayMs}ms)",
        )
        assertTrue(problems.isEmpty(), "incoming audio was not continuous across the handoff: ${problems.take(5)}")
    }

    /** Real-time paced provide loop; returns right-channel carrier amplitude per 20ms frame. */
    private fun pump(player: AudioPlayer, mixer: GuildMixer, amps: MutableList<Double>, stop: () -> Boolean) {
        val buffer = ByteBuffer.allocate(format.maximumChunkSize())
        val frame = MutableAudioFrame().apply { setBuffer(buffer) }
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.nanoTime()
        var frames = 0L
        while (System.currentTimeMillis() < deadline) {
            // Lavalink sends a frame every 20ms whether or not the player has one.
            val got = player.provide(frame)
            amps += if (got && !frame.isTerminator) rightAmplitude(buffer.array(), frame.dataLength) else 0.0
            frames++
            mixer.drain()
            if (frames % 5L == 0L) mixer.poll()
            if (stop()) return
            val due = started + frames * 20_000_000L
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
        }
    }

    private fun rightAmplitude(bytes: ByteArray, length: Int): Double {
        val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var n = 0
        while (bb.remaining() >= 4) {
            bb.short
            val r = bb.short / 32768.0
            sum += r * r
            n++
        }
        return if (n == 0) 0.0 else sqrt(sum / n) * sqrt(2.0)
    }

    private fun slowServer(body: ByteArray, delayMs: () -> Long): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/incoming.wav") { ex ->
            try {
                Thread.sleep(delayMs())
                val range = ex.requestHeaders.getFirst("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                ex.responseHeaders.add("Content-Type", "audio/wav")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val len = body.size - start
                if (range != null) {
                    ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                    ex.sendResponseHeaders(206, len)
                } else {
                    ex.sendResponseHeaders(200, len)
                }
                ex.responseBody.use { it.write(body, start.toInt(), len.toInt()) }
            } catch (_: Exception) {
                // Client hung up (stop/seek); nothing to do.
            } finally {
                ex.close()
            }
        }
        server.start()
        cleanup += { server.stop(0) }
        return "http://127.0.0.1:${server.address.port}/incoming.wav"
    }

    private fun wavFile(prefix: String, seconds: Int, sample: (Int, Int) -> Pair<Double, Double>): File {
        val file = File.createTempFile(prefix, ".wav")
        cleanup += { file.delete() }
        file.writeBytes(wavBytes(seconds, sample))
        return file
    }

    private fun wavBytes(seconds: Int, sample: (Int, Int) -> Pair<Double, Double>): ByteArray {
        val samples = sampleRate * seconds
        val dataLen = samples * 4
        val bytes = ByteArrayOutputStream(44 + dataLen)
        DataOutputStream(bytes).use { out ->
            fun intLE(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun shortLE(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            out.writeBytes("RIFF"); intLE(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); intLE(16); shortLE(1); shortLE(2); intLE(sampleRate); intLE(sampleRate * 4); shortLE(4); shortLE(16)
            out.writeBytes("data"); intLE(dataLen)
            for (i in 0 until samples) {
                val (l, r) = sample(i, samples)
                shortLE((l * 32767).toInt())
                shortLE((r * 32767).toInt())
            }
        }
        return bytes.toByteArray()
    }

    private fun loadTrack(manager: DefaultAudioPlayerManager, identifier: String): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.complete(playlist.selectedTrack ?: playlist.tracks.first()) }
            override fun noMatches() { future.completeExceptionally(NoMatchException(identifier)) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(15, TimeUnit.SECONDS)
    }

    private fun iPlayer(guildId: Long, ap: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAudioPlayer" -> ap
            "getGuildId" -> guildId
            "getTrack" -> ap.playingTrack
            "isPlaying" -> ap.playingTrack != null
            "play" -> { ap.playTrack(args!![0] as AudioTrack); null }
            "stop" -> { ap.stopTrack(); null }
            else -> null
        }
    } as IPlayer
}
