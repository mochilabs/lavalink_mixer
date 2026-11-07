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
            assertTrue(headGrid.bpmConfidence > 0.25f, "recovered confidence=${headGrid.bpmConfidence}")
            // Phase should be projected into the period [0..500ms)
            assertTrue(headGrid.phaseMs in 0L..500L, "projected phase=${headGrid.phaseMs}")
        } finally {
            mixer.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `tail scan uses trimmed content end for resampled audio`() {
        val manager = manager()
        val file = introThenBeatFile("mixer-trimmed-bpm", introSec = 0, bpm = 120.0,
            totalSec = 50, contentEndSec = 28, sourceRate = 44100)
        val service = TrackAnalysisService(manager, 46L)
        try {
            val track = loadTrack(manager, file)
            val result = service.analyze(track, TrackAnalysisSettings(
                silence = true, normalization = false, bpm = true,
                silenceThresholdDb = -50f, silenceMinSoundMs = 1,
                silenceHeadScanMs = 5000, silenceTailScanMs = 25000, bpmScanMs = 12000,
            ))
            assertTrue(!result.aborted)
            val trim = assertNotNull(result.trim)
            assertTrue(trim.trailMs in 22_000..23_000, "trim=$trim")
            val tail = assertNotNull(result.bpmTail)
            assertEquals(120f, tail.bpm)
            val features = assertNotNull(result.featuresTail)
            assertNull(features.unknownReason, "a complete trimmed tail must be usable by the mixer")
            assertTrue(features.windowStartMs in 15_000..16_000, "tail starts at ${features.windowStartMs}")
            assertTrue(tail.phaseMs in 15_000..16_500, "tail phase=${tail.phaseMs}")

            val measured = assertNotNull(service.measureTailAnalysis(track, 12000, trim.trailMs))
            assertEquals(features.windowStartMs, measured.features.windowStartMs)
            assertNull(measured.features.unknownReason, "explicit tail reanalysis must validate the trimmed endpoint")
            assertEquals(120f, assertNotNull(measured.grid).bpm)
        } finally {
            service.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `trimmed tail analysis publishes usable grids for queued track`() {
        val manager = manager()
        val file = introThenBeatFile("mixer-trimmed-cache", introSec = 0, bpm = 120.0,
            totalSec = 50, contentEndSec = 28, sourceRate = 44100)
        val mixer = GuildMixer(47L, manager, MixerConfig().apply {
            bpmDetectionEnabled = true
            bpmScanMs = 12000
            silenceSkipEnabled = true
            silenceThresholdDb = -50f
            silenceMinSoundMs = 1
            silenceHeadScanMs = 5000
            silenceTailScanMs = 25000
        }, java.util.concurrent.Executor { it.run() })
        try {
            val track = loadTrack(manager, file)
            mixer.queueNext(CompletableFuture.completedFuture(track))
            mixer.analyzeBlocking(track)
            assertEquals(120f, assertNotNull(mixer.peekNextBpm(), "completed analysis must reach the playback cache").bpm)
            assertNull(assertNotNull(AnalysisCaches.featuresTail[track.identifier]).unknownReason)
            assertEquals(120f, assertNotNull(AnalysisCaches.bpmTail[track.identifier]).bpm)
            assertNull(mixer.maybeRetryAnalysis(), "complete trimmed scans must not exhaust retries")
        } finally {
            mixer.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `head projected from tail without local beat measurement keeps downbeat confidence at zero`() {
        val manager = manager()
        // 12s track, 6s drumless intro, 5s scan: smart retry is skipped (5000 + 10000 >= 12000),
        // so the head is synthesized from the 120 BPM tail and must not claim a verified downbeat.
        val file = introThenBeatFile("mixer-tail-projected-head", introSec = 6, bpm = 120.0, totalSec = 12)
        val service = TrackAnalysisService(manager, 48L)
        try {
            val track = loadTrack(manager, file)
            val result = service.analyze(track, TrackAnalysisSettings(
                silence = false, normalization = false, bpm = true,
                silenceThresholdDb = -50f, silenceMinSoundMs = 1,
                silenceHeadScanMs = 5000, silenceTailScanMs = 5000, bpmScanMs = 5000,
            ))
            val tail = assertNotNull(result.bpmTail)
            val head = assertNotNull(result.bpmHead)
            assertTrue(tail.downbeatConfidence > 0f, "tail has real beats (got ${tail.downbeatConfidence})")
            assertEquals(120f, head.bpm, 1.5f)
            assertEquals(0f, head.downbeatConfidence, "extrapolated head must not pass downbeat gates")
            assertEquals(0f, head.phraseConfidence, "extrapolated head must not pass phrase gates")
        } finally {
            service.destroy()
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
    private fun introThenBeatFile(prefix: String, introSec: Int, bpm: Double, totalSec: Int,
                                  contentEndSec: Int = totalSec, sourceRate: Int = sampleRate): File {
        val file = File.createTempFile(prefix, ".wav")
        val total = sourceRate * totalSec
        val introSamples = sourceRate * introSec
        val period = (sourceRate * 60.0 / bpm).toInt()
        val dataLen = total * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(1)
            out.writeIntLE(sourceRate)
            out.writeIntLE(sourceRate * 2)
            out.writeShortLE(2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (pos in 0 until total) {
                if (pos >= contentEndSec * sourceRate) {
                    out.writeShortLE(0)
                } else if (pos < introSamples) {
                    val tone = (Math.sin(2 * Math.PI * 440.0 * pos / sourceRate) * 1000).toInt()
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
