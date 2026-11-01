package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

internal data class TrackAnalysisSettings(
    val silence: Boolean,
    val normalization: Boolean,
    val bpm: Boolean,
    val silenceThresholdDb: Float,
    val silenceMinSoundMs: Long,
    val silenceHeadScanMs: Long,
    val silenceTailScanMs: Long,
    val bpmScanMs: Long,
)

internal data class TrackAnalysisResult(
    val trim: TrimPoints?,
    val loudnessRms: Float?,
    val bpm: BpmDetector.BpmResult?,
    val aborted: Boolean,
    val bpmHead: BeatGrid? = null,
    val bpmTail: BeatGrid? = null,
    val featuresHead: TransitionFeatures? = null,
    val featuresTail: TransitionFeatures? = null,
)

/**
 * Per-guild analysis lifecycle counters. Every field and method must be
 * accessed while holding [GuildMixer]'s lock.
 */
internal class AnalysisState {
    var active: Boolean = false
    var retryAtMs: Long = 0L
    /** Consecutive analysis failures for [failureId]; retries give up after 3. */
    var failures: Int = 0
    var failureId: String? = null
    /** A completed scan with no beat grid is a valid result, not a retryable failure. */
    var completedBpmIdentifier: String? = null

    fun noteFailureLocked(identifier: String) {
        if (failureId == identifier) {
            failures++
        } else {
            failureId = identifier
            failures = 1
        }
    }

    fun clearFailureIfMatchingLocked(identifier: String) {
        if (failureId == identifier) {
            failures = 0
            failureId = null
        }
    }

    fun canRetryLocked(identifier: String, now: Long): Boolean {
        if (active) return false
        if (failureId == identifier && failures >= 3) return false
        if (now - retryAtMs < 250) return false
        retryAtMs = now
        return true
    }
}

internal class TrackAnalysisService(
    private val playerManager: AudioPlayerManager,
    private val guildId: Long,
) {
    private val playerLock = Any()

    private data class SinglePassResult(
        val leadMs: Long,
        val trailMs: Long,
        val loudnessRms: Float?,
        val bpm: BpmDetector.BpmResult?,
        val bpmHead: BeatGrid? = null,
        val bpmTail: BeatGrid? = null,
        val featuresHead: TransitionFeatures? = null,
        val featuresTail: TransitionFeatures? = null,
    )

    @Volatile
    private var analysisPlayer: AudioPlayer? = null

    @Volatile
    private var analysisFactory: SiphonFactory? = null

    @Volatile
    private var analysisBroken = false

    fun beginScan() {
        analysisBroken = false
    }

    fun analyze(track: AudioTrack, settings: TrackAnalysisSettings): TrackAnalysisResult {
        val factory = ensureAnalysisPlayer().second
        try {
            val duration = try {
                track.duration
            } catch (e: Exception) {
                -1L
            }
            val seekable = try {
                track.isSeekable
            } catch (e: Exception) {
                false
            }
            val scanCap = maxOf(settings.silenceHeadScanMs, settings.silenceTailScanMs)
            var lead = 0L
            var tail = 0L
            var loudness: Float? = null
            var bpm: BpmDetector.BpmResult? = null
            var bpmHead: BeatGrid? = null
            var bpmTail: BeatGrid? = null
            var featuresHead: TransitionFeatures? = null
            var featuresTail: TransitionFeatures? = null
            var bpmAttempted = false
            val shortTrack = duration in 1..minOf(scanCap, 30000)

            if (shortTrack && (settings.silence || settings.normalization || settings.bpm)) {
                val result = analyzeSinglePass(track, factory, duration, settings)
                lead = result.leadMs
                tail = result.trailMs
                loudness = result.loudnessRms
                if (settings.bpm) {
                    bpm = result.bpm
                    bpmHead = result.bpmHead
                    bpmTail = result.bpmTail
                    featuresHead = result.featuresHead
                    featuresTail = result.featuresTail
                    bpmAttempted = true
                }
            } else if (settings.silence || settings.normalization) {
                if (settings.silence) {
                    lead = scanHead(track, factory, settings)
                    if (seekable && duration > 0 && duration < Long.MAX_VALUE / 2) {
                        tail = scanTail(track, factory, duration, settings)
                    }
                }
                if (settings.normalization) {
                    loudness = measureLoudnessBlocking(track, factory)
                }
            }

            if (settings.bpm && !bpmAttempted) {
                val headFrames = collectFrom(track, factory, 0, msToSamples(settings.bpmScanMs))
                if (headFrames.isNotEmpty()) {
                    bpm = BpmDetector.analyze(headFrames, sampleRate = SAMPLE_RATE)
                    bpmHead = BpmDetector.analyzeGrid(headFrames, sampleRate = SAMPLE_RATE, windowStartMs = 0L)
                    featuresHead = TransitionFeatureAnalyzer.analyze(headFrames, grid = bpmHead)
                }
                if (seekable && duration > 0 && duration < Long.MAX_VALUE / 2) {
                    val contentEndMs = if (tail > 0) (duration - tail).coerceAtLeast(0L) else duration
                    val tailStartMs = (contentEndMs - settings.bpmScanMs).coerceAtLeast(0L)
                    if (tailStartMs == 0L) {
                        bpmTail = bpmHead
                        featuresTail = featuresHead
                    } else {
                        // Collect +2000ms buffer to compensate for decoder seek imprecision and ensure full scan window
                        val tailFrames = collectFrom(track, factory, tailStartMs, msToSamples(settings.bpmScanMs + 2000))
                        if (tailFrames.isNotEmpty()) {
                            bpmTail = BpmDetector.analyzeGrid(tailFrames, sampleRate = SAMPLE_RATE, windowStartMs = tailStartMs)
                            featuresTail = TransitionFeatureAnalyzer.analyze(tailFrames, tailStartMs, bpmTail)
                        }
                    }
                }
            }
            val guardedLead = if (duration > 0 && lead >= duration - 500) 0 else lead.coerceAtLeast(0)
            val trim = if (settings.silence) {
                TrimPoints(guardedLead, tail.coerceAtLeast(0))
            } else {
                null
            }
            if (duration > 0 && duration < Long.MAX_VALUE / 2) {
                featuresHead = featuresHead?.let { guardFeatureWindow(it, minOf(duration, settings.bpmScanMs)) }
                featuresTail = featuresTail?.let { guardFeatureWindow(it, duration) }
            }
            return TrackAnalysisResult(trim, loudness, bpm, analysisBroken, bpmHead, bpmTail, featuresHead, featuresTail)
        } finally {
            stopTrack()
        }
    }

    fun measureBaseline(track: AudioTrack): Float? {
        val factory = ensureAnalysisPlayer().second
        return try {
            measureLoudnessBlocking(track, factory)
        } finally {
            stopTrack()
        }
    }

    data class TailAnalysis(val grid: BeatGrid?, val features: TransitionFeatures)

    fun measureTailGrid(track: AudioTrack, bpmScanMs: Long): BeatGrid? = measureTailAnalysis(track, bpmScanMs)?.grid

    fun measureTailAnalysis(track: AudioTrack, bpmScanMs: Long): TailAnalysis? {
        val factory = ensureAnalysisPlayer().second
        return try {
            val duration = try {
                track.duration
            } catch (e: Exception) {
                -1L
            }
            val seekable = try {
                track.isSeekable
            } catch (e: Exception) {
                false
            }
            if (!seekable || duration <= 0 || duration >= Long.MAX_VALUE / 2) {
                return null
            }
            val tailStartMs = (duration - bpmScanMs).coerceAtLeast(0L)
            // Collect +2000ms buffer to compensate for decoder seek imprecision and ensure full scan window
            val collected = collectFrom(track, factory, tailStartMs, msToSamples(bpmScanMs + 2000))
            if (collected.isEmpty()) null else {
                val grid = BpmDetector.analyzeGrid(collected, sampleRate = SAMPLE_RATE, windowStartMs = tailStartMs)
                TailAnalysis(grid, guardFeatureWindow(TransitionFeatureAnalyzer.analyze(collected, tailStartMs, grid), duration))
            }
        } finally {
            stopTrack()
        }
    }

    fun measureTailSilence(track: AudioTrack, settings: TrackAnalysisSettings): TrimPoints? {
        val factory = ensureAnalysisPlayer().second
        return try {
            val duration = try {
                track.duration
            } catch (e: Exception) {
                -1L
            }
            val seekable = try {
                track.isSeekable
            } catch (e: Exception) {
                false
            }
            if (!seekable || duration <= 0 || duration >= Long.MAX_VALUE / 2) {
                return null
            }
            val tail = scanTail(track, factory, duration, settings)
            if (analysisBroken) null else TrimPoints(0L, tail.coerceAtLeast(0L))
        } finally {
            stopTrack()
        }
    }

    private fun guardFeatureWindow(features: TransitionFeatures, expectedEndMs: Long): TransitionFeatures =
        if (analysisBroken || features.windowEndMs < expectedEndMs - 250) features.copy(unknownReason = "incomplete_window") else features

    fun destroy() {
        val player = synchronized(playerLock) {
            analysisFactory = null
            analysisPlayer.also { analysisPlayer = null }
        }
        try {
            player?.destroy()
        } catch (e: Exception) {
            log.warn("analysis destroy failed on guild {}", guildId, e)
        }
    }

    private fun ensureAnalysisPlayer(): Pair<AudioPlayer, SiphonFactory> = synchronized(playerLock) {
        val currentPlayer = analysisPlayer
        val currentFactory = analysisFactory
        if (currentPlayer != null && currentFactory != null) {
            return@synchronized currentPlayer to currentFactory
        }
        val player = playerManager.createPlayer()
        val factory = SiphonFactory(SiphonQueue(maxFrames = 1, maxSamples = 1))
        player.setFilterFactory(factory)
        analysisPlayer = player
        analysisFactory = factory
        player to factory
    }

    private fun scanHead(track: AudioTrack, factory: SiphonFactory, settings: TrackAnalysisSettings): Long {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = analysisPlayer ?: return 0
            val clone = track.makeClone()
            sub.playTrack(clone)
            val scanner = SilenceAnalyzer.LeadScanner(
                SilenceAnalyzer.thresholdAmp(settings.silenceThresholdDb),
                msToSamples(settings.silenceMinSoundMs),
            )
            val cap = msToSamples(settings.silenceHeadScanMs)
            var collected = 0
            val pumpFrame = newProvideFrame()
            val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
            while (collected < cap && System.currentTimeMillis() < deadline && !analysisBroken) {
                if (sub.playingTrack !== clone) break
                if (tap.pendingFrames == 0) {
                    pumpSub(sub, pumpFrame)
                    continue
                }
                while (tap.pendingFrames > 0 && collected < cap) {
                    val n = minOf(1920, cap - collected)
                    val chunk = Array(2) { FloatArray(n) }
                    val took = tap.take(n, 2, chunk)
                    if (took <= 0) break
                    collected += took
                    if (scanner.feed(chunk, took) != null) break
                }
                if (scanner.resultMs != null) break
            }
            stopTrack()
            return scanner.resultMs ?: 0
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun scanTail(
        track: AudioTrack,
        factory: SiphonFactory,
        duration: Long,
        settings: TrackAnalysisSettings,
    ): Long {
        val startMs = (duration - settings.silenceTailScanMs).coerceAtLeast(0)
        val collected = collectFrom(track, factory, startMs, msToSamples(settings.silenceTailScanMs + 2000))
        return trailingSilenceMs(
            collected,
            settings.silenceThresholdDb,
            msToSamples(settings.silenceMinSoundMs),
        )
    }

    private fun analyzeSinglePass(
        track: AudioTrack,
        factory: SiphonFactory,
        duration: Long,
        settings: TrackAnalysisSettings,
    ): SinglePassResult {
        val collected = collectFrom(track, factory, 0, msToSamples(duration))
        return analyzeFrames(
            collected,
            settings.silenceThresholdDb,
            msToSamples(settings.silenceMinSoundMs),
            settings.normalization,
            settings.bpm,
        )
    }

    private fun analyzeFrames(
        frames: List<Array<FloatArray>>,
        silenceThresholdDb: Float,
        silenceMinSoundSamples: Int,
        measureLoudness: Boolean,
        measureBpm: Boolean,
    ): SinglePassResult {
        if (frames.isEmpty()) return SinglePassResult(0L, 0L, null, null)
        var total = 0L
        for (frame in frames) total += frame[0].size
        val totalMs = total * 1000 / SAMPLE_RATE
        val threshold = SilenceAnalyzer.thresholdAmp(silenceThresholdDb)
        val first = SilenceAnalyzer.findFirstSound(frames, threshold, silenceMinSoundSamples)
        val lastEnd = SilenceAnalyzer.findLastSound(frames, threshold, silenceMinSoundSamples)
        val lead = if (first < 0) 0 else first
        val trail = if (lastEnd < 0) totalMs else (totalMs - lastEnd).coerceAtLeast(0)
        val loudness = if (measureLoudness) {
            val meter = LoudnessMeter()
            meter.feed(frames)
            meter.finish()
        } else null
        val bpm = if (measureBpm) BpmDetector.analyze(frames) else null
        val grid = if (measureBpm) BpmDetector.analyzeGrid(frames, windowStartMs = 0L) else null
        val features = if (measureBpm) TransitionFeatureAnalyzer.analyze(frames, grid = grid) else null
        return SinglePassResult(lead, trail, loudness, bpm, bpmHead = grid, bpmTail = grid, featuresHead = features, featuresTail = features)
    }

    private fun trailingSilenceMs(
        frames: List<Array<FloatArray>>,
        silenceThresholdDb: Float,
        silenceMinSoundSamples: Int,
    ): Long {
        if (frames.isEmpty()) return 0
        var total = 0L
        for (frame in frames) total += frame[0].size
        val collectedMs = total * 1000 / SAMPLE_RATE
        val lastEnd = SilenceAnalyzer.findLastSound(
            frames,
            SilenceAnalyzer.thresholdAmp(silenceThresholdDb),
            silenceMinSoundSamples,
        )
        return if (lastEnd < 0) collectedMs else (collectedMs - lastEnd).coerceAtLeast(0)
    }

    private fun measureLoudnessBlocking(track: AudioTrack, factory: SiphonFactory): Float? {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = analysisPlayer ?: return null
            val clone = track.makeClone()
            sub.playTrack(clone)
            val meter = LoudnessMeter()
            val pumpFrame = newProvideFrame()
            val durationMs = try {
                clone.duration
            } catch (e: Exception) {
                -1L
            }
            val capMs = if (durationMs in 1..(10 * 60_000L)) durationMs else SCAN_TIMEOUT_MS
            val deadline = System.currentTimeMillis() + capMs + LOUDNESS_GRACE_MS
            while (System.currentTimeMillis() < deadline && !analysisBroken) {
                if (sub.playingTrack !== clone && tap.pendingFrames == 0) break
                if (tap.pendingFrames == 0) {
                    pumpSub(sub, pumpFrame)
                    continue
                }
                while (tap.pendingFrames > 0) {
                    val chunk = Array(2) { FloatArray(1920) }
                    val took = tap.take(1920, 2, chunk)
                    if (took <= 0) break
                    meter.feed(chunk, 0, took)
                }
            }
            stopTrack()
            return meter.finish()
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun measureBpmBlocking(
        track: AudioTrack,
        factory: SiphonFactory,
        bpmScanMs: Long,
    ): BpmDetector.BpmResult? {
        val collected = collectFrom(track, factory, 0, msToSamples(bpmScanMs))
        if (collected.isEmpty()) return null
        return BpmDetector.analyze(collected, sampleRate = SAMPLE_RATE)
    }

    private fun collectFrom(
        track: AudioTrack,
        factory: SiphonFactory,
        startMs: Long,
        capSamples: Int,
    ): List<Array<FloatArray>> {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = analysisPlayer ?: return emptyList()
            val clone = track.makeClone()
            if (startMs > 0) {
                try {
                    clone.position = startMs
                } catch (e: Exception) {
                    log.warn("scan seek failed on guild {}", guildId, e)
                    return emptyList()
                }
            }
            sub.playTrack(clone)
            val frames = mutableListOf<Array<FloatArray>>()
            var collected = 0
            val pumpFrame = newProvideFrame()
            val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
            while (collected < capSamples && System.currentTimeMillis() < deadline && !analysisBroken) {
                if (tap.pendingFrames == 0) {
                    if (sub.playingTrack !== clone) break
                    pumpSub(sub, pumpFrame)
                    continue
                }
                while (tap.pendingFrames > 0 && collected < capSamples) {
                    val n = minOf(1920, capSamples - collected)
                    val chunk = Array(2) { FloatArray(n) }
                    val took = tap.take(n, 2, chunk)
                    if (took <= 0) break
                    frames.add(if (took < n) Array(2) { c -> chunk[c].copyOf(took) } else chunk)
                    collected += took
                }
            }
            stopTrack()
            return frames
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun pumpSub(sub: AudioPlayer, frame: MutableAudioFrame) {
        var burst = 0
        while (burst < ANALYSIS_PUMP_BURST && !analysisBroken) {
            try {
                if (!sub.provide(frame)) break
            } catch (e: Exception) {
                analysisBroken = true
                log.warn("analysis pump failed on guild {}", guildId, e)
                break
            }
            burst++
        }
        try {
            Thread.sleep(if (burst > 0) ANALYSIS_PUMP_SLEEP_MS else ANALYSIS_IDLE_SLEEP_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            analysisBroken = true
        }
    }

    private fun newProvideFrame(): MutableAudioFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    private fun provideBufferSize(): Int = try {
        playerManager.configuration?.outputFormat?.maximumChunkSize()
            ?: StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    } catch (t: Throwable) {
        StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    }

    private fun stopTrack() {
        try {
            analysisPlayer?.stopTrack()
        } catch (e: Exception) {
            log.warn("analysis cleanup stop failed on guild {}", guildId, e)
        }
    }

    private fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)

    private companion object {
        private val log = LoggerFactory.getLogger(TrackAnalysisService::class.java)
        private const val SAMPLE_RATE = 48000
        private const val SCAN_TIMEOUT_MS = 45_000L
        private const val LOUDNESS_GRACE_MS = 5_000L
        private const val ANALYSIS_PUMP_BURST = 8
        private const val ANALYSIS_PUMP_SLEEP_MS = 10L
        private const val ANALYSIS_IDLE_SLEEP_MS = 20L
    }
}
