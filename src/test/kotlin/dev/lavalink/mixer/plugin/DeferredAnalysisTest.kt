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
 * Preload-time silence analysis is skipped while the secondary player is busy
 * (overlay, overlap, another scan). That skip used to strand the queued track
 * in "pending" until the end-of-file fallback fired — the overlap plan never
 * became available, so the transition landed without a crossfade.
 * [GuildMixer.maybeRetryAnalysis] must resume the scan once the sub frees up.
 */
class DeferredAnalysisTest {
    private val sampleRate = 48000

    @Test
    fun `analysis skipped on a busy sub retries after it frees up`() {
        val manager = manager()
        val file = toneFile("mixer-deferred-analysis", 440.0, 8)
        try {
            val mixer = GuildMixer(31L, manager, MixerConfig().apply {
                silenceSkipEnabled = true
                silenceHeadScanMs = 1500
                silenceTailScanMs = 2000
            })
            val track = loadTrack(manager, file)
            mixer.queueNext(CompletableFuture.completedFuture(track))

            // Silence skip on and no cached trim yet: the slot waits for analysis.
            assertEquals("pending", mixer.analysisState())

            // Busy sub: neither a retry nor a direct scan may run.
            mixer.beginXfadeForTest(sampleRate)
            assertNull(mixer.maybeRetryAnalysis(), "analysis retried while the sub was busy")
            val skippedAt = System.currentTimeMillis()
            mixer.analyzeBlocking(track)
            assertTrue(System.currentTimeMillis() - skippedAt < 1_000, "a busy-sub scan must skip immediately")
            assertNull(mixer.trimForTest(track.identifier), "a skipped analysis must not cache a trim")
            mixer.endXfadeForTest()

            // Free sub: the retry hands out a scannable clone, throttled.
            val retry = mixer.maybeRetryAnalysis()
            assertNotNull(retry, "no analysis retry offered once the sub freed up")
            assertEquals(track.identifier, retry.identifier)
            assertNull(mixer.maybeRetryAnalysis(), "retry is not throttled — it must not rescan in a hot loop")

            mixer.analyzeBlocking(retry)

            val trim = mixer.trimForTest(track.identifier)
            assertNotNull(trim, "the retried analysis never cached a trim")
            assertEquals("ready", mixer.analysisState(), "the analyzed slot was never promoted to ready")
            assertNull(mixer.maybeRetryAnalysis(), "retries keep offered after the trim is cached")
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
