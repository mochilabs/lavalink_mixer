package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import dev.arbjerg.lavalink.api.IPlayer
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies single-open analysis guarantees:
 * 1. Every `TrackAnalysisService.analyze` call invokes `process()` at most once.
 * 2. Preanalysis warms loudness on <=10m tracks so arming does not re-decode.
 * 3. Playing-track backfill opens the main track at most once for all missing metrics.
 * 4. Long (>10m) tracks with normalization off use in-flight seeking in one open.
 * 5. `AnalysisState` limits failure retries and honors cooldown.
 */
class SinglePassAnalysisTest {
    private val sampleRate = 48000

    private class CountingTrack(
        private val delegate: InternalAudioTrack,
        val processCalls: AtomicInteger = AtomicInteger(0),
        private val durationOverrideMs: Long? = null,
    ) : DelegatedAudioTrack(delegate.info) {
        override fun process(executor: LocalAudioTrackExecutor) {
            processCalls.incrementAndGet()
            delegate.process(executor)
        }

        override fun getDuration(): Long = durationOverrideMs ?: delegate.duration

        override fun isSeekable(): Boolean = delegate.isSeekable

        override fun getSourceManager(): AudioSourceManager = delegate.sourceManager

        override fun makeClone(): AudioTrack =
            CountingTrack(delegate.makeClone() as InternalAudioTrack, processCalls, durationOverrideMs)
    }

    @Test
    fun `normal track opens only once even with normalization off and all detectors on`() {
        val manager = manager()
        val file = paddedBeatWav("single-pass-norm-off", bpm = 120.0, leadSilenceSec = 1, activeSec = 38, trailSilenceSec = 2)
        val service = TrackAnalysisService(manager, 901L)
        try {
            val base = loadTrack(manager, file) as InternalAudioTrack
            val counted = CountingTrack(base)
            val result = service.analyze(
                counted,
                TrackAnalysisSettings(
                    silence = true,
                    normalization = false,
                    bpm = true,
                    silenceThresholdDb = -50f,
                    silenceMinSoundMs = 50,
                    silenceHeadScanMs = 15_000,
                    silenceTailScanMs = 15_000,
                    bpmScanMs = 15_000,
                    key = true,
                    keyScanMs = 10_000,
                ),
            )
            assertEquals(1, counted.processCalls.get(), "all-detector scan without normalization must open source once")
            assertFalse(result.aborted)
            assertNull(result.loudnessRms)
            val trim = assertNotNull(result.trim)
            assertTrue(trim.leadMs in 900..1150, "lead=${trim.leadMs}")
            assertTrue(trim.trailMs in 1900..2150, "trail=${trim.trailMs}")
            assertNotNull(result.bpm)
            assertNotNull(result.bpmHead)
            assertNotNull(result.bpmTail)
            assertNotNull(result.featuresHead)
            assertNotNull(result.featuresTail)
            assertNull(result.featuresHead?.unknownReason)
            assertNull(result.featuresTail?.unknownReason)
            assertNotNull(result.key)
        } finally {
            service.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `normal track with normalization on opens once and computes loudness plus all detectors`() {
        val manager = manager()
        val file = paddedBeatWav("single-pass-norm-on", bpm = 120.0, leadSilenceSec = 1, activeSec = 34, trailSilenceSec = 2)
        val service = TrackAnalysisService(manager, 902L)
        try {
            val base = loadTrack(manager, file) as InternalAudioTrack
            val counted = CountingTrack(base)
            var provisionalCalled = false
            val result = service.analyze(
                counted,
                TrackAnalysisSettings(
                    silence = true,
                    normalization = true,
                    bpm = true,
                    silenceThresholdDb = -50f,
                    silenceMinSoundMs = 50,
                    silenceHeadScanMs = 15_000,
                    silenceTailScanMs = 15_000,
                    bpmScanMs = 15_000,
                    key = true,
                    keyScanMs = 15_000,
                ),
                onLoudnessEstimate = { provisionalCalled = true },
            )
            assertEquals(1, counted.processCalls.get(), "all-detector scan with normalization must open source once")
            assertFalse(result.aborted)
            assertTrue(provisionalCalled)
            assertNotNull(result.loudnessRms)
            assertNotNull(result.trim)
            assertNotNull(result.bpmHead)
            assertNotNull(result.bpmTail)
            assertNotNull(result.key)
        } finally {
            service.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `long track over 10m with normalization off uses in-flight seeks in a single open`() {
        val manager = manager()
        // 610s @ 8kHz mono (~9.7 MB) genuinely exceeds SEQUENTIAL_MAX_MS (600s)
        val file = long8kMonoBeatWav("single-pass-long-seek", bpm = 120.0, totalSec = 610, leadSilenceSec = 1, trailSilenceSec = 2)
        val service = TrackAnalysisService(manager, 903L)
        try {
            val base = loadTrack(manager, file) as InternalAudioTrack
            val counted = CountingTrack(base)
            val result = service.analyze(
                counted,
                TrackAnalysisSettings(
                    silence = true,
                    normalization = false,
                    bpm = true,
                    silenceThresholdDb = -50f,
                    silenceMinSoundMs = 50,
                    silenceHeadScanMs = 5_000,
                    silenceTailScanMs = 5_000,
                    bpmScanMs = 10_000,
                    key = true,
                    keyScanMs = 10_000,
                ),
            )
            assertEquals(1, counted.processCalls.get(), ">10m seeked scan must open source only once")
            assertFalse(result.aborted)
            val trim = assertNotNull(result.trim)
            assertTrue(trim.leadMs in 900..1200, "lead=${trim.leadMs}")
            assertTrue(trim.trailMs in 1800..2250, "trail=${trim.trailMs}")
            assertNotNull(result.bpmHead)
            assertNotNull(result.bpmTail)
            assertNotNull(result.featuresTail)
            assertNull(result.featuresTail?.unknownReason, "tail reason=${result.featuresTail?.unknownReason}")
            assertNotNull(result.key)
        } finally {
            service.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `preanalysis includes loudness on sequential pass so arming needs zero extra opens`() {
        val manager = manager()
        val file = paddedBeatWav("single-pass-pre-arm", bpm = 120.0, leadSilenceSec = 0, activeSec = 12, trailSilenceSec = 1)
        val mixer = GuildMixer(904L, manager, MixerConfig().apply {
            normalizationEnabled = true
            silenceSkipEnabled = true
            bpmDetectionEnabled = true
            keyDetectionEnabled = true
            bpmScanMs = 8_000
            keyScanMs = 8_000
            silenceHeadScanMs = 5_000
            silenceTailScanMs = 5_000
        }, Executor { it.run() })
        try {
            val base = loadTrack(manager, file) as InternalAudioTrack
            val counted = CountingTrack(base)
            assertTrue(mixer.requestPreanalysis(counted))
            assertEquals(1, counted.processCalls.get(), "preanalysis must open track once")
            assertNotNull(AnalysisCaches.norm[counted.identifier])
            assertNotNull(AnalysisCaches.trim[counted.identifier])
            assertNotNull(AnalysisCaches.bpmHead[counted.identifier])
            assertNotNull(AnalysisCaches.key[counted.identifier])

            mixer.analyzeBlocking(counted, cacheOnly = false)
            assertEquals(1, counted.processCalls.get(), "arming after preanalysis must reuse all caches with 0 opens")
        } finally {
            mixer.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `playing track backfill fills norm, trim, bpm tail, and key in a single open`() {
        val manager = manager()
        val playingFile = paddedBeatWav("single-pass-backfill-play", bpm = 120.0, leadSilenceSec = 0, activeSec = 10, trailSilenceSec = 1)
        val incomingFile = paddedBeatWav("single-pass-backfill-in", bpm = 120.0, leadSilenceSec = 0, activeSec = 10, trailSilenceSec = 1)
        val tasks = ArrayDeque<Runnable>()
        val mixer = GuildMixer(905L, manager, MixerConfig().apply {
            normalizationEnabled = true
            silenceSkipEnabled = true
            bpmDetectionEnabled = true
            keyDetectionEnabled = true
            bpmScanMs = 6_000
            keyScanMs = 6_000
            silenceHeadScanMs = 4_000
            silenceTailScanMs = 4_000
        }, Executor { tasks.addLast(it) })
        val player = manager.createPlayer()
        try {
            val playingCounted = CountingTrack(loadTrack(manager, playingFile) as InternalAudioTrack)
            val incomingCounted = CountingTrack(loadTrack(manager, incomingFile) as InternalAudioTrack)

            mixer.attachMain(iPlayer(905L, player))
            player.playTrack(playingCounted)
            val deadline = System.currentTimeMillis() + 5_000L
            while (playingCounted.processCalls.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            val playingBeforeBackfill = playingCounted.processCalls.get()
            assertEquals(1, playingBeforeBackfill, "main player itself should open playing track once")

            // Discard any queued startup scan on playing track so backfill handles all missing caches.
            tasks.clear()
            mixer.analyzeBlocking(incomingCounted)

            assertEquals(1, incomingCounted.processCalls.get(), "incoming scan must open once")
            assertEquals(
                playingBeforeBackfill + 1,
                playingCounted.processCalls.get(),
                "backfill for playing track must open once, not separately for tail/norm/silence",
            )
            assertNotNull(AnalysisCaches.norm[playingCounted.identifier])
            assertNotNull(AnalysisCaches.trim[playingCounted.identifier])
            assertNotNull(AnalysisCaches.featuresTail[playingCounted.identifier])
            assertNotNull(AnalysisCaches.key[playingCounted.identifier])
        } finally {
            player.stopTrack()
            player.destroy()
            mixer.destroy()
            manager.shutdown()
            playingFile.delete()
            incomingFile.delete()
        }
    }

    @Test
    fun `clean EOF with container metadata duration drift does not falsely trigger incomplete_window`() {
        val manager = manager()
        val file = paddedBeatWav("single-pass-eof-drift", bpm = 120.0, leadSilenceSec = 0, activeSec = 16, trailSilenceSec = 0)
        val service = TrackAnalysisService(manager, 906L)
        try {
            val base = loadTrack(manager, file) as InternalAudioTrack
            // Simulate YouTube header duration over-reporting by 1200ms vs decoded PCM EOF.
            val counted = CountingTrack(base, durationOverrideMs = base.duration + 1200L)
            val result = service.analyze(
                counted,
                TrackAnalysisSettings(
                    silence = true,
                    normalization = false,
                    bpm = true,
                    silenceThresholdDb = -50f,
                    silenceMinSoundMs = 50,
                    silenceHeadScanMs = 5_000,
                    silenceTailScanMs = 5_000,
                    bpmScanMs = 8_000,
                ),
            )
            assertEquals(1, counted.processCalls.get())
            assertFalse(result.aborted)
            assertNull(result.featuresHead?.unknownReason)
            assertNull(result.featuresTail?.unknownReason, "clean EOF within drift must not mark incomplete_window")
        } finally {
            service.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `main track start unifies baseline loudness and preanalysis into a single open`() {
        val manager = manager()
        val file = paddedBeatWav("single-pass-main-start", bpm = 120.0, leadSilenceSec = 0, activeSec = 10, trailSilenceSec = 1)
        val tasks = ArrayDeque<Runnable>()
        val mixer = GuildMixer(907L, manager, MixerConfig().apply {
            normalizationEnabled = true
            silenceSkipEnabled = true
            bpmDetectionEnabled = true
            keyDetectionEnabled = true
            bpmScanMs = 6_000
            keyScanMs = 6_000
            silenceHeadScanMs = 4_000
            silenceTailScanMs = 4_000
        }, Executor { tasks.addLast(it) })
        val player = manager.createPlayer()
        try {
            val counted = CountingTrack(loadTrack(manager, file) as InternalAudioTrack)
            mixer.attachMain(iPlayer(907L, player))
            player.playTrack(counted)
            val deadline = System.currentTimeMillis() + 5_000L
            while (counted.processCalls.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals(1, tasks.size, "main track start must enqueue 1 combined scan, not baseline + preanalysis")
            tasks.removeFirst().run()
            assertEquals(2, counted.processCalls.get(), "main playback (1) + combined analysis (1) = 2 opens total")
            assertNotNull(AnalysisCaches.norm[counted.identifier])
            assertNotNull(AnalysisCaches.trim[counted.identifier])
            assertNotNull(AnalysisCaches.bpmHead[counted.identifier])
            assertNotNull(AnalysisCaches.key[counted.identifier])
        } finally {
            player.stopTrack()
            player.destroy()
            mixer.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    @Test
    fun `analysis state enforces failure cooldown and stops after one failed retry`() {
        val state = AnalysisState()
        val id = "yt-err-track"

        state.noteFailureLocked(id, now = 1_000L)
        // Inside 5s cooldown -> blocked
        assertFalse(state.canRetryLocked(id, now = 1_500L))
        // Past 5s cooldown -> 1st retry allowed
        assertTrue(state.canRetryLocked(id, now = 6_100L))

        // Fails a second time -> exhausted
        state.noteFailureLocked(id, now = 7_000L)
        assertFalse(state.canRetryLocked(id, now = 60_000L))
    }

    private fun iPlayer(guildId: Long, ap: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAudioPlayer" -> ap
            "getGuildId" -> guildId
            "getTrack" -> ap.playingTrack
            else -> null
        }
    } as IPlayer

    private fun manager(): DefaultAudioPlayerManager = DefaultAudioPlayerManager().apply {
        registerSourceManager(LocalAudioSourceManager())
        configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
    }

    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.complete(playlist.selectedTrack ?: playlist.tracks.first()) }
            override fun noMatches() { future.completeExceptionally(NoMatchException(file.absolutePath)) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun paddedBeatWav(
        prefix: String,
        bpm: Double,
        leadSilenceSec: Int,
        activeSec: Int,
        trailSilenceSec: Int,
    ): File {
        val file = File.createTempFile(prefix, ".wav")
        val leadSamples = leadSilenceSec * sampleRate
        val activeSamples = activeSec * sampleRate
        val totalSamples = (leadSilenceSec + activeSec + trailSilenceSec) * sampleRate
        val beatInterval = (sampleRate * 60.0 / bpm).toInt()
        val clickSamples = sampleRate / 40
        val dataLen = totalSamples * 2 * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVEfmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(2)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 4)
            out.writeShortLE(4)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until totalSamples) {
                val (left, right) = if (i in leadSamples until (leadSamples + activeSamples)) {
                    val pos = i - leadSamples
                    val inBeat = pos % beatInterval
                    val click = if (inBeat < clickSamples) 12000.0 * (1.0 - inBeat.toDouble() / clickSamples) else 0.0
                    val phase = 2.0 * PI * 220.0 * pos / sampleRate
                    val l = (click + sin(phase) * 4000.0).toInt().coerceIn(-32000, 32000)
                    val r = (click + kotlin.math.cos(phase) * 4000.0).toInt().coerceIn(-32000, 32000)
                    l to r
                } else 0 to 0
                out.writeShortLE(left)
                out.writeShortLE(right)
            }
        }
        return file
    }

    private fun long8kMonoBeatWav(
        prefix: String,
        bpm: Double,
        totalSec: Int,
        leadSilenceSec: Int,
        trailSilenceSec: Int,
    ): File {
        val sr = 8000
        val file = File.createTempFile(prefix, ".wav")
        val totalSamples = totalSec * sr
        val leadSamples = leadSilenceSec * sr
        val activeEnd = (totalSec - trailSilenceSec) * sr
        val beatInterval = (sr * 60.0 / bpm).toInt()
        val clickSamples = sr / 25
        val dataLen = totalSamples * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVEfmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(1)
            out.writeIntLE(sr)
            out.writeIntLE(sr * 2)
            out.writeShortLE(2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until totalSamples) {
                val sample = if (i in leadSamples until activeEnd) {
                    val pos = i - leadSamples
                    val inBeat = pos % beatInterval
                    val click = if (inBeat < clickSamples) 12000.0 * (1.0 - inBeat.toDouble() / clickSamples) else 0.0
                    // Amplitude stays >= 2000 (> -50 dB) continuously across sine cycles.
                    val tone = 5000.0 + sin(2.0 * PI * 220.0 * pos / sr) * 3000.0
                    (click + tone).toInt().coerceIn(-32000, 32000)
                } else 0
                out.writeShortLE(sample)
            }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
}
