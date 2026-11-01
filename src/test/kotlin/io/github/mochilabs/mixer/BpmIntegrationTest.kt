package io.github.mochilabs.mixer

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tempo detection is opt-in, best-effort metadata: enabling it must decode a
 * head window on preload and expose the BPM/first-beat through
 * [GuildMixer.peekNextBpm], but never gate promotion (a track still promotes
 * on silence/normalization even when BPM analysis finds nothing).
 */
class BpmIntegrationTest {
    private val sampleRate = 48000

    @Test
    fun `real cache only scan does not populate normalization or arm playback`() {
        val manager = manager()
        val file = clickFile("mixer-cache-only", 124.0, 6)
        val mixer = GuildMixer(44L, manager, MixerConfig().apply {
            bpmDetectionEnabled = true
            normalizationEnabled = true
            silenceSkipEnabled = true
            bpmScanMs = 5000
        }, java.util.concurrent.Executor { it.run() })
        try {
            val track = loadTrack(manager, file)
            mixer.normalizeForTest(0.4f)
            mixer.analyzeBlocking(track, cacheOnly = true)
            assertNotNull(AnalysisCaches.featuresHead[track.identifier])
            assertNotNull(AnalysisCaches.bpmHead[track.identifier])
            assertNull(AnalysisCaches.norm[track.identifier])
            assertNull(AnalysisCaches.trim[track.identifier])
            assertEquals(0.4f, mixer.currentTrackGain())
            assertTrue(!mixer.hasNext())
        } finally {
            mixer.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `enabled bpm detection caches and exposes tempo for a click track`() {
        val manager = manager()
        val file = clickFile("mixer-bpm", 120.0, 8)
        try {
            val mixer = GuildMixer(41L, manager, MixerConfig().apply {
                bpmDetectionEnabled = true
                bpmScanMs = 8000
            }, java.util.concurrent.Executor { it.run() })
            val track = loadTrack(manager, file)
            mixer.queueNext(CompletableFuture.completedFuture(track))
            mixer.analyzeBlocking(track)

            val bpm = mixer.peekNextBpm()
            assertNotNull(bpm, "click track must yield a tempo")
            assertTrue(bpm.bpm in 119f..121f, "expected ~120 BPM, got ${bpm.bpm}")
            mixer.destroy()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `disabled bpm detection never populates the cache`() {
        val manager = manager()
        val file = clickFile("mixer-bpm-off", 120.0, 8)
        try {
            val mixer = GuildMixer(42L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
            val track = loadTrack(manager, file)
            mixer.queueNext(CompletableFuture.completedFuture(track))
            mixer.analyzeBlocking(track)

            assertNull(mixer.peekNextBpm(), "BPM cache must stay empty while detection is disabled")
            mixer.destroy()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `updateBpm toggles detection and scan window`() {
        val manager = manager()
        val mixer = GuildMixer(43L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
        mixer.updateBpm(true, 60000)
        assertTrue(mixer.bpmDetectionEnabled)
        assertEquals(60000L, mixer.bpmScanMs)
        mixer.updateBpm(true, 1) // below clamp floor
        assertEquals(5000L, mixer.bpmScanMs)
        mixer.updateBpm(false)
        assertTrue(!mixer.bpmDetectionEnabled)
        mixer.destroy()
    }
    @Test
    fun `smart retry detects tempo when head is drumless intro and projects head phase`() {
        val manager = manager()
        val file = introThenBeatFile("mixer-smart-retry", introSec = 6, bpm = 120.0, totalSec = 20)
        val mixer = GuildMixer(45L, manager, MixerConfig().apply {
            bpmDetectionEnabled = true
            bpmScanMs = 5000
        }, java.util.concurrent.Executor { it.run() })
        try {
            val track = loadTrack(manager, file)
            mixer.analyzeBlocking(track, cacheOnly = true)
            val headGrid = AnalysisCaches.bpmHead[track.identifier]
            assertNotNull(headGrid, "bpmHead should be recovered by smart retry")
            assertTrue(kotlin.math.abs(headGrid.bpm - 120f) < 1.5f, "recovered bpm=${headGrid.bpm}")
            assertTrue(headGrid.confidence > 0.25f, "recovered confidence=${headGrid.confidence}")
            // Phase should be projected into the period [0..500ms)
            assertTrue(headGrid.phaseMs in 0L..500L, "projected phase=${headGrid.phaseMs}")
        } finally {
            mixer.destroy()
            manager.shutdown()
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

    /** Mono 16-bit PCM WAV of short clicks on every beat (a click track). */
    private fun clickFile(prefix: String, bpm: Double, seconds: Int): File {
        val file = File.createTempFile(prefix, ".wav")
        val total = sampleRate * seconds
        val period = (sampleRate * 60.0 / bpm).toInt()
        val dataLen = total * 2 // mono 16-bit
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1) // PCM
            out.writeShortLE(1) // mono
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2) // byte rate
            out.writeShortLE(2) // block align
            out.writeShortLE(16) // bits
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (pos in 0 until total) {
                val click = (pos % period) < 100
                out.writeShortLE(if (click) 25000 else 0)
            }
        }
        return file
    }
    private fun introThenBeatFile(prefix: String, introSec: Int, bpm: Double, totalSec: Int): File {
        val file = File.createTempFile(prefix, ".wav")
        val total = sampleRate * totalSec
        val introSamples = sampleRate * introSec
        val period = (sampleRate * 60.0 / bpm).toInt()
        val dataLen = total * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(1)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2)
            out.writeShortLE(2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (pos in 0 until total) {
                if (pos < introSamples) {
                    val tone = (Math.sin(2 * Math.PI * 440.0 * pos / sampleRate) * 1000).toInt()
                    out.writeShortLE(tone)
                } else {
                    val click = ((pos - introSamples) % period) < 100
                    out.writeShortLE(if (click) 25000 else 0)
                }
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
