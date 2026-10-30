package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Production regression: arm() schedules analyzeBlocking in the background,
 * and the scan used to decode on the LIVE sub player. When the JIT window
 * opened during that scan, startOverlap's playTrack() stole the decoder and
 * the analysis cleanup's stopTrack() then killed the just-started live
 * overlap ("crossfade aborted after 9ms ... dropped=12363f" — subPos=0,
 * subOut=0). The analysis decoder is now dedicated, so a scan finishing
 * mid-crossfade must leave the live overlap untouched.
 */
class AnalysisIsolatedFromLiveSubTest {
    private val sampleRate = 48000

    @Test
    fun `an analysis completing mid-crossfade leaves the live overlap running`() {
        val manager = manager()
        val outgoing = toneFile("mixer-iso-out", 440.0, 10)
        val incoming = toneFile("mixer-iso-in", 880.0, 10)
        try {
            val config = MixerConfig().apply {
                crossfadeEnabled = true
                crossfadeMs = 3000
                fadeInMs = 0
                maskMs = 0
                silenceSkipEnabled = true
                silenceHeadScanMs = 1500
                silenceTailScanMs = 2000
            }
            val mixer = GuildMixer(1L, manager, config, java.util.concurrent.Executor { it.run() })
            val player = manager.createPlayer()
            player.setFilterFactory { _, _, output -> mutableListOf(mixer.newFilter(output)) }
            mixer.attachMain(iPlayer(1L, player))

            val trackA = loadTrack(manager, outgoing)
            val trackB = loadTrack(manager, incoming)
            player.playTrack(trackA)

            // arm() shape: slot armed + background analysis scheduled. The
            // forced promotion keeps readyNextTrack set even without a trim.
            val armResult = mixer.arm(1L, "iso-test-arm", trackB, false, null)
            assertNotNull(armResult as? ArmResult.Success, "arm failed: $armResult")

            // Drive playback until the overlap actually starts.
            pumpUntil(player, mixer) { mixer.isXfadeActive() }
            assertTrue(mixer.isXfadeActive(), "the crossfade never armed: poll() never planned an overlap")

            // A background analysis (e.g. the loudness pass arm() scheduled)
            // finishing now used to stopTrack() the live sub and abort the
            // overlap within milliseconds of it starting.
            mixer.analyzeBlocking(trackB.makeClone())

            assertTrue(mixer.isXfadeActive(), "analysis cleanup killed the live overlap mid-crossfade")
            mixer.destroy()
        } finally {
            outgoing.delete()
            incoming.delete()
        }
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

    private fun pumpUntil(player: AudioPlayer, mixer: GuildMixer, stop: () -> Boolean) {
        val frame = MutableAudioFrame().apply {
            setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize()))
        }
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

    private fun pace(started: Long, frames: Long) {
        val expectedNanos = frames * 20_000_000L
        val realNanos = System.nanoTime() - started
        if (realNanos < expectedNanos) Thread.sleep((expectedNanos - realNanos) / 1_000_000L)
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        return m
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