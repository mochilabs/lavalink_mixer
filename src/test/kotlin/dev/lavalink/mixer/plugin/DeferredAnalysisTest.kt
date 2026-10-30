package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Preload-time silence analysis fills the trim cache; until it lands the
 * queued track stays "pending" and the end-of-file fallback fires the
 * transition without a crossfade. [GuildMixer.maybeRetryAnalysis] must hand
 * out a scannable clone (throttled) so the retry completes the scan.
 */
class DeferredAnalysisTest {
    private val sampleRate = 48000

    @Test
    fun `analysis completes and promotes the pending slot without a live overlap deferring it`() {
        val manager = manager()
        val file = toneFile("mixer-deferred-analysis", 440.0, 8)
        try {
            val mixer = GuildMixer(31L, manager, MixerConfig().apply {
                silenceSkipEnabled = true
                silenceHeadScanMs = 1500
                silenceTailScanMs = 2000
            }, java.util.concurrent.Executor { it.run() })
            val track = loadTrack(manager, file)
            mixer.queueNext(CompletableFuture.completedFuture(track))

            // Silence skip on and no cached trim yet: the slot waits for analysis.
            assertEquals("pending", mixer.analysisState())

            // The analysis decoder is dedicated (a live overlap no longer
            // defers the scan — that regression is pinned in
            // AnalysisIsolatedFromLiveSubTest), so analyzeBlocking completes
            // here and caches the trim.
            mixer.analyzeBlocking(track)

            // Cached: the slot is promoted and no retry is offered.
            assertEquals("ready", mixer.analysisState(), "the analyzed slot was never promoted to ready")
            assertNull(mixer.maybeRetryAnalysis(), "retries keep offered after the trim is cached")

            // Promotion only happens when the scan is not broken; the trim
            // is what made the slot ready.
            val trim = mixer.trimForTest(track.identifier)
            assertNotNull(trim, "the completed analysis never cached a trim")
            assertEquals(0L, trim.leadMs, "a tone that starts immediately must have no leading silence")
            mixer.destroy()
        } finally {
            file.delete()
        }
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        return m
    }

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
