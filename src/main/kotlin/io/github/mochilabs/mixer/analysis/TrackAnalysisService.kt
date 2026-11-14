package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
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
    val key: Boolean = false,
    val keyScanMs: Long = 30000,
    /**
     * Grids below this confidence are rejected by the planner. A head or tail
     * grid under it is treated like a missing one: the scan retries further
     * into the track and fills from the other end, keeping the stronger grid.
     */
    val bpmMinConfidence: Float = 0.30f,
)

internal data class TrackAnalysisResult(
    val trim: TrimPoints?,
    val loudnessRms: Float?,
    val bpm: BpmDetector.BpmResult?,
    val aborted: Boolean,
    val bpmHead: ExtendedBeatGrid? = null,
    val bpmTail: ExtendedBeatGrid? = null,
    val featuresHead: TransitionFeatures? = null,
    val featuresTail: TransitionFeatures? = null,
    val key: KeyAnalysis? = null,
)

/**
 * Per-guild analysis lifecycle counters. Every field and method must be
 * accessed while holding [GuildMixer]'s lock.
 */
internal class AnalysisState {
    var active: Boolean = false
    var retryAtMs: Long = 0L
    /** Consecutive analysis failures for [failureId]; retries are capped at 1 with backoff. */
    var failures: Int = 0
    var failureId: String? = null
    var lastFailureAtMs: Long = 0L
    /** A completed scan with no beat grid is a valid result, not a retryable failure. */
    var completedBpmIdentifier: String? = null

    fun noteFailureLocked(identifier: String, now: Long = System.currentTimeMillis()) {
        lastFailureAtMs = now
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
            lastFailureAtMs = 0L
        }
    }

    fun canRetryLocked(identifier: String, now: Long): Boolean {
        if (active) return false
        if (failureId == identifier) {
            // Allow at most 1 retry after a failure, and wait out the failure cooldown
            // so transient YouTube/source errors do not hammer /player repeatedly.
            if (failures > MAX_FAILURE_RETRIES) return false
            if (now - lastFailureAtMs < FAILURE_RETRY_COOLDOWN_MS) return false
        }
        if (now - retryAtMs < DEFERRED_RETRY_THROTTLE_MS) return false
        retryAtMs = now
        return true
    }

    private companion object {
        const val MAX_FAILURE_RETRIES = 1
        const val FAILURE_RETRY_COOLDOWN_MS = 5_000L
        const val DEFERRED_RETRY_THROTTLE_MS = 250L
    }
}

internal class TrackAnalysisService(
    private val playerManager: AudioPlayerManager,
    private val guildId: Long,
) {
    private val playerLock = Any()

    private data class HeadEval(
        val bpm: BpmDetector.BpmResult?,
        val grid: ExtendedBeatGrid?,
        val features: TransitionFeatures?,
        val key: KeyAnalysis?,
    )

    private fun evaluateHeadBpm(
        headFrames: List<Array<FloatArray>>,
        settings: TrackAnalysisSettings,
        existingKey: KeyAnalysis?,
    ): HeadEval {
        val bpmFrames = sliceFrames(headFrames, 0L, msToSamplesLong(settings.bpmScanMs))
        if (bpmFrames.isEmpty()) return HeadEval(null, null, null, existingKey)
        val key = if (existingKey == null && settings.key && settings.keyScanMs <= settings.bpmScanMs) {
            KeyDetector.analyze(bpmFrames, msToSamples(settings.keyScanMs))
        } else existingKey
        val bpm = BpmDetector.analyze(bpmFrames, sampleRate = SAMPLE_RATE)
        var grid = BpmDetector.analyzeExtendedGrid(bpmFrames, sampleRate = SAMPLE_RATE, windowStartMs = 0L)
        val features = TransitionFeatureAnalyzer.analyze(bpmFrames, grid = grid)
        grid = mergePhraseConfidence(grid, features)
        return HeadEval(bpm, grid, features, key)
    }

    @Volatile
    private var analysisPlayer: AudioPlayer? = null

    @Volatile
    private var analysisFactory: SiphonFactory? = null

    @Volatile
    private var analysisBroken = false

    @Volatile
    private var activeScanTrack: AudioTrack? = null

    @Volatile
    private var finishedScanTrack: AudioTrack? = null

    fun beginScan() {
        analysisBroken = false
    }

    /**
     * [onLoudnessEstimate] receives a provisional integrated loudness after the first
     * [PROVISIONAL_LOUDNESS_MS] of decoded audio, before the full-track scan finishes.
     *
     * Every track analysis opens the source at most once (`sub.playTrack(clone)` once):
     * - Tracks up to [SEQUENTIAL_MAX_MS] (or any track with normalization enabled, or
     *   unseekable streams) run a single sequential pass.
     * - Longer tracks with normalization disabled seek inside the same open playback
     *   session, avoiding repeated source opens and InnerTube `/player` requests.
     */
    fun analyze(
        track: AudioTrack,
        settings: TrackAnalysisSettings,
        onLoudnessEstimate: ((Float) -> Unit)? = null,
    ): TrackAnalysisResult {
        if (!settings.silence && !settings.normalization && !settings.bpm && !settings.key) {
            return TrackAnalysisResult(null, null, null, aborted = false)
        }
        val (sub, factory) = ensureAnalysisPlayer()
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
            val hasFiniteDuration = duration > 0 && duration < Long.MAX_VALUE / 2
            val needTail = seekable && hasFiniteDuration && (settings.silence || settings.bpm)
            val useSequential = settings.normalization ||
                !seekable ||
                !hasFiniteDuration ||
                (needTail && duration <= SEQUENTIAL_MAX_MS)

            if (useSequential) {
                runSequentialSinglePass(sub, factory, track, duration, seekable, hasFiniteDuration, settings, onLoudnessEstimate)
            } else {
                runSeekedSingleOpen(sub, factory, track, duration, seekable, hasFiniteDuration, settings)
            }
        } finally {
            activeScanTrack = null
            stopTrack()
        }
    }

    /** Phrase confidence lives in [TransitionFeatures], not the BPM detector; copy it onto the grid. */
    private fun mergePhraseConfidence(grid: ExtendedBeatGrid?, features: TransitionFeatures?): ExtendedBeatGrid? {
        if (grid == null) return null
        val phrase = features?.phraseConfidence ?: 0f
        if (phrase <= 0f) return grid
        return grid.copy(phraseConfidence = phrase)
    }

    /**
     * Measures the missing end on the frames already decoded for it, searching only
     * within [MISSING_END_BPM_BAND] of [reference]'s tempo. Null when nothing is held
     * or the window still has no usable pulse.
     */
    private fun measureNear(frames: List<Array<FloatArray>>, windowStartMs: Long, reference: ExtendedBeatGrid): ExtendedBeatGrid? {
        if (frames.isEmpty()) return null
        return BpmDetector.analyzeExtendedGrid(
            frames,
            sampleRate = SAMPLE_RATE,
            minBpm = reference.bpm * (1f - MISSING_END_BPM_BAND),
            maxBpm = reference.bpm * (1f + MISSING_END_BPM_BAND),
            windowStartMs = windowStartMs,
        )
    }

    /**
     * Grid and features for an end whose own pulse search failed or never ran.
     * Re-measures near [reference]'s tempo first. Otherwise the reference tempo is projected
     * onto the window: beat phase is moved to the first beat inside it (window coordinates,
     * not the reference window's), and downbeat and phrase stay unverified. Features computed
     * against a projected grid drop their phrase fields, so they never claim a boundary the
     * grid cannot back.
     */
    private fun fillMissingEnd(
        frames: List<Array<FloatArray>>,
        windowStartMs: Long,
        reference: ExtendedBeatGrid,
    ): Pair<ExtendedBeatGrid, TransitionFeatures?> {
        val remeasured = measureNear(frames, windowStartMs, reference)
        val grid = remeasured ?: projectTempoOnly(reference, windowStartMs)
        if (frames.isEmpty()) return grid to null
        val features = TransitionFeatureAnalyzer.analyze(frames, windowStartMs, grid)
        if (remeasured != null) return mergePhraseConfidence(grid, features)!! to features
        return grid to features.copy(phraseConfidence = 0f, phraseBoundaryMs = -1L)
    }

    /**
     * Tempo-only grid extrapolated from the other end. Beat phase may drift across the
     * track, so downbeat and phrase stay unverified (0) and cannot pass the planner's
     * bar-alignment gates.
     */
    private fun projectTempoOnly(reference: ExtendedBeatGrid, windowStartMs: Long): ExtendedBeatGrid {
        val windowStartSample = windowStartMs.toDouble() * SAMPLE_RATE / 1000.0
        val period = reference.periodSamples
        val bar = period * 4.0
        return reference.copy(
            phaseSample = firstOnOrAfter(reference.phaseSample.toDouble(), period, windowStartSample),
            bpmConfidence = reference.bpmConfidence * 0.85f,
            downbeatSample = firstOnOrAfter(reference.downbeatSample.toDouble(), bar, windowStartSample),
            downbeatConfidence = 0f,
            phraseConfidence = 0f,
        )
    }

    /** First point of the lattice `anchor + k * step` (k integer) at or after [from]. */
    private fun firstOnOrAfter(anchor: Double, step: Double, from: Double): Long =
        Math.round(anchor + Math.ceil((from - anchor) / step) * step)

    fun measureBaseline(track: AudioTrack, onLoudnessEstimate: ((Float) -> Unit)? = null): Float? =
        analyze(
            track,
            TrackAnalysisSettings(
                silence = false,
                normalization = true,
                bpm = false,
                silenceThresholdDb = -60f,
                silenceMinSoundMs = 120,
                silenceHeadScanMs = 0,
                silenceTailScanMs = 0,
                bpmScanMs = 0,
                key = false,
            ),
            onLoudnessEstimate = onLoudnessEstimate,
        ).loudnessRms

    data class TailAnalysis(val grid: ExtendedBeatGrid?, val features: TransitionFeatures)

    fun measureTailGrid(track: AudioTrack, bpmScanMs: Long, tailSilenceMs: Long = 0L): ExtendedBeatGrid? =
        measureTailAnalysis(track, bpmScanMs, tailSilenceMs)?.grid

    fun measureTailAnalysis(track: AudioTrack, bpmScanMs: Long, tailSilenceMs: Long = 0L): TailAnalysis? {
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
            val effectiveTailSilence = if (tailSilenceMs > 0L) tailSilenceMs else {
                try {
                    track.identifier?.let { AnalysisCaches.trim[it]?.trailMs } ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
            val contentEndMs = if (effectiveTailSilence > 0L) (duration - effectiveTailSilence).coerceAtLeast(0L) else duration
            val tailStartMs = (contentEndMs - bpmScanMs).coerceAtLeast(0L)
            // Collect +2000ms buffer to compensate for decoder seek imprecision and ensure full scan window
            val collected = collectFrom(track, factory, tailStartMs, msToSamples(bpmScanMs + 2000))
            if (collected.isEmpty()) null else {
                var grid = BpmDetector.analyzeExtendedGrid(collected, sampleRate = SAMPLE_RATE, windowStartMs = tailStartMs)
                val features = guardFeatureWindow(TransitionFeatureAnalyzer.analyze(collected, tailStartMs, grid), contentEndMs)
                grid = mergePhraseConfidence(grid, features)
                TailAnalysis(grid, features)
            }
        } finally {
            activeScanTrack = null
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
            val startMs = (duration - settings.silenceTailScanMs).coerceAtLeast(0)
            val collected = collectFrom(track, factory, startMs, msToSamples(settings.silenceTailScanMs + 2000))
            val tail = trailingSilenceMs(
                collected,
                settings.silenceThresholdDb,
                msToSamples(settings.silenceMinSoundMs),
            )
            if (analysisBroken) null else TrimPoints(0L, tail.coerceAtLeast(0L))
        } finally {
            activeScanTrack = null
            stopTrack()
        }
    }

    private class TailRingBuffer(private val maxSamples: Long) {
        private data class RingChunk(
            val data: Array<FloatArray>,
            val len: Int,
            val startSample: Long,
        )

        private val chunks = ArrayDeque<RingChunk>()
        private var bufferedSamples: Long = 0L
        var endSample: Long = 0L
            private set

        fun push(chunk: Array<FloatArray>, length: Int) {
            if (length <= 0) return
            val copied = if (length == chunk[0].size) {
                Array(chunk.size) { c -> chunk[c].copyOf() }
            } else {
                Array(chunk.size) { c -> chunk[c].copyOfRange(0, length) }
            }
            chunks.addLast(RingChunk(copied, length, endSample))
            bufferedSamples += length
            endSample += length
            while (chunks.size > 1 && bufferedSamples - chunks.first().len >= maxSamples) {
                val dropped = chunks.removeFirst()
                bufferedSamples -= dropped.len
            }
        }

        fun sliceRange(startSample: Long, stopSample: Long): List<Array<FloatArray>> {
            if (chunks.isEmpty() || stopSample <= startSample) return emptyList()
            val out = mutableListOf<Array<FloatArray>>()
            for (rc in chunks) {
                val rcEnd = rc.startSample + rc.len
                if (rcEnd <= startSample || rc.startSample >= stopSample) continue
                val from = maxOf(0L, startSample - rc.startSample).toInt()
                val to = minOf(rc.len.toLong(), stopSample - rc.startSample).toInt()
                if (from < to) {
                    out.add(Array(rc.data.size) { c -> rc.data[c].copyOfRange(from, to) })
                }
            }
            return out
        }

        fun sliceLast(samplesFromEnd: Long): List<Array<FloatArray>> =
            sliceRange(maxOf(0L, endSample - samplesFromEnd), endSample)
    }

    private fun runSequentialSinglePass(
        sub: AudioPlayer,
        factory: SiphonFactory,
        track: AudioTrack,
        duration: Long,
        seekable: Boolean,
        hasFiniteDuration: Boolean,
        settings: TrackAnalysisSettings,
        onLoudnessEstimate: ((Float) -> Unit)?,
    ): TrackAnalysisResult {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val clone = startScanTrack(sub, track)
            val needTail = seekable && hasFiniteDuration && (settings.silence || settings.bpm)
            val readToEof = settings.normalization || (needTail && hasFiniteDuration && duration <= SEQUENTIAL_MAX_MS)
            val headCollectCapSamples = maxOf(
                if (settings.bpm) msToSamples(settings.bpmScanMs) else 0,
                if (settings.key) msToSamples(settings.keyScanMs) else 0,
            )
            val silenceHeadCapSamples = if (settings.silence) msToSamples(settings.silenceHeadScanMs) else 0
            val streamSampleCap = if (readToEof) {
                Long.MAX_VALUE
            } else {
                maxOf(headCollectCapSamples, silenceHeadCapSamples).toLong().coerceAtLeast(1L)
            }

            val tailRingCapMs = (if (settings.silence) settings.silenceTailScanMs else 0L) +
                (if (settings.bpm) settings.bpmScanMs + 2000L else 0L) + 2000L
            val tailRing = if (needTail) TailRingBuffer(msToSamplesLong(tailRingCapMs)) else null

            val leadScanner = if (settings.silence) {
                SilenceAnalyzer.LeadScanner(
                    SilenceAnalyzer.thresholdAmp(settings.silenceThresholdDb),
                    msToSamples(settings.silenceMinSoundMs),
                )
            } else null
            val meter = if (settings.normalization) LoudnessMeter() else null
            val estimateAfterSamples = PROVISIONAL_LOUDNESS_MS * SAMPLE_RATE / 1000
            var provisionalSent = onLoudnessEstimate == null

            val headFrames = mutableListOf<Array<FloatArray>>()
            var headSamplesCollected = 0
            var fedSamples = 0L

            val bpmScanSamples = if (settings.bpm) msToSamples(settings.bpmScanMs) else 0
            var headBpmEvaluated = false
            var headResult: BpmDetector.BpmResult? = null
            var bpmHead: ExtendedBeatGrid? = null
            var featuresHead: TransitionFeatures? = null
            var key: KeyAnalysis? = null

            // Smart-retry window captured in-stride if the track's head lacks confident beats
            var retryCandidateStartMs = -1L
            var retryCaptureEndMs = Long.MAX_VALUE
            var retryStartSample = Long.MAX_VALUE
            var retryEndSample = -1L
            val retryFramesWithOffset = mutableListOf<Pair<Long, Array<FloatArray>>>()

            val timeoutBudgetMs = if (hasFiniteDuration && duration <= SEQUENTIAL_MAX_MS) {
                maxOf(SCAN_TIMEOUT_MS, duration / 2 + LOUDNESS_GRACE_MS)
            } else {
                SCAN_TIMEOUT_MS + LOUDNESS_GRACE_MS
            }
            val deadline = System.currentTimeMillis() + timeoutBudgetMs
            val pumpFrame = newProvideFrame()
            var lastChunkPosMs = 0L

            while (fedSamples < streamSampleCap && System.currentTimeMillis() < deadline && !analysisBroken) {
                if (sub.playingTrack !== clone && tap.pendingFrames == 0) break
                if (tap.pendingFrames == 0) {
                    pumpSub(sub, pumpFrame)?.let { lastChunkPosMs = it }
                    continue
                }
                while (tap.pendingFrames > 0 && fedSamples < streamSampleCap) {
                    val toTake = minOf(1920L, streamSampleCap - fedSamples).toInt()
                    val chunk = Array(2) { FloatArray(toTake) }
                    val took = tap.take(toTake, 2, chunk)
                    if (took <= 0) break
                    val exact = if (took < toTake) Array(2) { c -> chunk[c].copyOf(took) } else chunk

                    meter?.feed(exact, 0, took)
                    if (leadScanner != null && fedSamples < silenceHeadCapSamples && leadScanner.resultMs == null) {
                        val remain = minOf(took.toLong(), silenceHeadCapSamples - fedSamples).toInt()
                        leadScanner.feed(exact, remain)
                    }
                    if (headSamplesCollected < headCollectCapSamples) {
                        val takeHead = minOf(took, headCollectCapSamples - headSamplesCollected)
                        headFrames.add(
                            if (takeHead < took) Array(2) { c -> exact[c].copyOfRange(0, takeHead) } else exact,
                        )
                        headSamplesCollected += takeHead
                    }
                    tailRing?.push(exact, took)

                    val chunkStartSample = fedSamples
                    fedSamples += took

                    if (settings.bpm && !headBpmEvaluated && headSamplesCollected >= bpmScanSamples) {
                        headBpmEvaluated = true
                        val eval = evaluateHeadBpm(headFrames, settings, key)
                        headResult = eval.bpm
                        bpmHead = eval.grid
                        featuresHead = eval.features
                        key = eval.key
                        val weakHead = bpmHead == null || bpmHead!!.bpmConfidence < settings.bpmMinConfidence
                        if (weakHead && seekable && hasFiniteDuration && duration > settings.bpmScanMs * 1.5) {
                            val candidate = minOf(duration / 4, 60_000L).coerceAtLeast(settings.bpmScanMs)
                            val knownLead = leadScanner?.resultMs ?: 0L
                            val tentativeStart = maxOf(candidate, knownLead).coerceAtLeast(0L)
                            if (tentativeStart + 10_000L < duration) {
                                retryCandidateStartMs = candidate
                                retryStartSample = msToSamplesLong(candidate)
                                retryCaptureEndMs = maxOf(candidate, if (settings.silence) settings.silenceHeadScanMs else 0L) +
                                    settings.bpmScanMs + 2000L
                                retryEndSample = msToSamplesLong(retryCaptureEndMs)
                            }
                        }
                    }

                    if (leadScanner?.resultMs != null && retryCandidateStartMs >= 0L) {
                        val exactRetryStart = maxOf(retryCandidateStartMs, leadScanner.resultMs!!).coerceAtLeast(0L)
                        retryStartSample = msToSamplesLong(exactRetryStart)
                        retryEndSample = msToSamplesLong(exactRetryStart + settings.bpmScanMs + 2000L)
                    }

                    if (fedSamples > retryStartSample && chunkStartSample < retryEndSample) {
                        val sliceFrom = maxOf(0L, retryStartSample - chunkStartSample).toInt()
                        val sliceTo = minOf(took.toLong(), retryEndSample - chunkStartSample).toInt()
                        if (sliceFrom < sliceTo) {
                            val slice = if (sliceFrom == 0 && sliceTo == took) {
                                exact
                            } else {
                                Array(2) { c -> exact[c].copyOfRange(sliceFrom, sliceTo) }
                            }
                            retryFramesWithOffset.add((chunkStartSample + sliceFrom) to slice)
                        }
                    }

                    if (meter != null && !provisionalSent && fedSamples >= estimateAfterSamples) {
                        provisionalSent = true
                        val provisional = meter.finish()
                        if (provisional > -70f) onLoudnessEstimate?.invoke(provisional)
                    }
                }
            }

            var eofReached = readToEof &&
                !analysisBroken &&
                (finishedScanTrack === clone || (sub.playingTrack !== clone && tap.pendingFrames == 0))
            val loudness = meter?.finish()

            // If normalization stopped by timeout mid-way through a >10m track, jump to tail once
            // inside the same open playback session so silence & tail BPM still complete.
            var tailFramesFromSeek: List<Array<FloatArray>> = emptyList()
            var tailSeekStartMs = 0L
            var tailSeekTotalSamples = 0L
            if (readToEof && !eofReached && !analysisBroken && needTail && hasFiniteDuration &&
                sub.playingTrack === clone
            ) {
                val tailZoneStartMs = earliestTailSeekMs(duration, settings)
                if (tailZoneStartMs > lastChunkPosMs) {
                    val seekDeadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
                    val seekRes = seekAndCollect(sub, clone, tap, tailZoneStartMs, Long.MAX_VALUE, seekDeadline, pumpFrame)
                    tailFramesFromSeek = seekRes.frames
                    tailSeekStartMs = tailZoneStartMs
                    tailSeekTotalSamples = seekRes.samples
                    eofReached = seekRes.eofReached
                }
            }

            val lead = leadScanner?.resultMs ?: 0L
            val actualDurationMs = when {
                tailFramesFromSeek.isNotEmpty() -> tailSeekStartMs + tailSeekTotalSamples * 1000L / SAMPLE_RATE
                else -> fedSamples * 1000L / SAMPLE_RATE
            }

            val finalRetryStartMs = if (retryCandidateStartMs >= 0L) {
                maxOf(retryCandidateStartMs, lead).coerceAtLeast(0L)
            } else -1L
            val retryFrames = if (finalRetryStartMs >= 0L && finalRetryStartMs + 10_000L < duration) {
                val minStartSample = msToSamplesLong(finalRetryStartMs)
                val maxEndSample = msToSamplesLong(finalRetryStartMs + settings.bpmScanMs + 2000L)
                retryFramesWithOffset.mapNotNull { (offset, frame) ->
                    val end = offset + frame[0].size
                    if (end <= minStartSample || offset >= maxEndSample) return@mapNotNull null
                    val from = maxOf(0L, minStartSample - offset).toInt()
                    val to = minOf(frame[0].size.toLong(), maxEndSample - offset).toInt()
                    if (from >= to) null else if (from == 0 && to == frame[0].size) frame else Array(2) { c -> frame[c].copyOfRange(from, to) }
                }
            } else emptyList()

            val effectiveDurationForGuard = if (eofReached && actualDurationMs > 0L && actualDurationMs >= duration - METADATA_EOF_DRIFT_MS) {
                actualDurationMs
            } else {
                duration
            }

            var tail = 0L
            var tailStartMs = 0L
            var tailBpmFrames: List<Array<FloatArray>> = emptyList()
            if (needTail) {
                if (tailFramesFromSeek.isNotEmpty()) {
                    val tailInfo = extractTailFromCollected(
                        tailFramesFromSeek,
                        tailSeekStartMs,
                        duration,
                        settings,
                    )
                    tail = tailInfo.trailMs
                    tailStartMs = tailInfo.bpmTailStartMs
                    tailBpmFrames = tailInfo.bpmTailFrames
                } else if (tailRing != null) {
                    if (settings.silence) {
                        val silenceSlice = tailRing.sliceLast(msToSamplesLong(settings.silenceTailScanMs))
                        tail = trailingSilenceMs(
                            silenceSlice,
                            settings.silenceThresholdDb,
                            msToSamples(settings.silenceMinSoundMs),
                        )
                    }
                    if (settings.bpm) {
                        val contentEndMs = if (tail > 0) (duration - tail).coerceAtLeast(0L) else duration
                        tailStartMs = (contentEndMs - settings.bpmScanMs).coerceAtLeast(0L)
                        if (tailStartMs > 0L) {
                            tailBpmFrames = tailRing.sliceRange(
                                msToSamplesLong(tailStartMs),
                                minOf(tailRing.endSample, msToSamplesLong(tailStartMs + settings.bpmScanMs + 2000L)),
                            )
                        }
                    }
                }
            }

            return finalizeAnalysisResult(
                duration = duration,
                effectiveDurationMs = effectiveDurationForGuard,
                seekable = seekable,
                hasFiniteDuration = hasFiniteDuration,
                eofReached = eofReached,
                settings = settings,
                lead = lead,
                tail = tail,
                loudness = loudness,
                headFrames = headFrames,
                precomputedBpm = headResult,
                precomputedBpmHead = bpmHead,
                precomputedFeaturesHead = featuresHead,
                precomputedKey = key,
                retryStartMs = finalRetryStartMs,
                retryFrames = retryFrames,
                tailStartMs = tailStartMs,
                tailFrames = tailBpmFrames,
            )
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun runSeekedSingleOpen(
        sub: AudioPlayer,
        factory: SiphonFactory,
        track: AudioTrack,
        duration: Long,
        seekable: Boolean,
        hasFiniteDuration: Boolean,
        settings: TrackAnalysisSettings,
    ): TrackAnalysisResult {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val clone = startScanTrack(sub, track)
            val pumpFrame = newProvideFrame()
            val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS

            val headCollectCapSamples = maxOf(
                if (settings.bpm) msToSamples(settings.bpmScanMs) else 0,
                if (settings.key) msToSamples(settings.keyScanMs) else 0,
            )
            val silenceHeadCapSamples = if (settings.silence) msToSamples(settings.silenceHeadScanMs) else 0
            val leadScanner = if (settings.silence) {
                SilenceAnalyzer.LeadScanner(
                    SilenceAnalyzer.thresholdAmp(settings.silenceThresholdDb),
                    msToSamples(settings.silenceMinSoundMs),
                )
            } else null

            val headFrames = mutableListOf<Array<FloatArray>>()
            var headSamplesCollected = 0
            var fedSamples = 0L

            while (System.currentTimeMillis() < deadline && !analysisBroken) {
                val leadDone = leadScanner == null || leadScanner.resultMs != null || fedSamples >= silenceHeadCapSamples
                val headDone = headSamplesCollected >= headCollectCapSamples
                if (leadDone && headDone) break
                if (sub.playingTrack !== clone && tap.pendingFrames == 0) break
                if (tap.pendingFrames == 0) {
                    pumpSub(sub, pumpFrame)
                    continue
                }
                while (tap.pendingFrames > 0) {
                    val stillNeedHead = headSamplesCollected < headCollectCapSamples
                    val stillNeedLead = leadScanner != null && leadScanner.resultMs == null && fedSamples < silenceHeadCapSamples
                    if (!stillNeedHead && !stillNeedLead) break
                    val maxStep = maxOf(
                        if (stillNeedHead) headCollectCapSamples - headSamplesCollected else 0,
                        if (stillNeedLead) silenceHeadCapSamples - fedSamples.toInt() else 0,
                    ).coerceIn(1, 1920)
                    val chunk = Array(2) { FloatArray(maxStep) }
                    val took = tap.take(maxStep, 2, chunk)
                    if (took <= 0) break
                    val exact = if (took < maxStep) Array(2) { c -> chunk[c].copyOf(took) } else chunk
                    if (stillNeedLead) {
                        leadScanner?.feed(exact, minOf(took, silenceHeadCapSamples - fedSamples.toInt()))
                    }
                    if (headSamplesCollected < headCollectCapSamples) {
                        val takeHead = minOf(took, headCollectCapSamples - headSamplesCollected)
                        headFrames.add(if (takeHead < took) Array(2) { c -> exact[c].copyOfRange(0, takeHead) } else exact)
                        headSamplesCollected += takeHead
                    }
                    fedSamples += took
                }
            }

            val lead = leadScanner?.resultMs ?: 0L
            var headResult: BpmDetector.BpmResult? = null
            var bpmHead: ExtendedBeatGrid? = null
            var featuresHead: TransitionFeatures? = null
            var key: KeyAnalysis? = null
            var retryStartMs = -1L
            var retryFrames: List<Array<FloatArray>> = emptyList()

            if (settings.bpm && headFrames.isNotEmpty()) {
                val eval = evaluateHeadBpm(headFrames, settings, key)
                headResult = eval.bpm
                bpmHead = eval.grid
                featuresHead = eval.features
                key = eval.key
                val weakHead = bpmHead == null || bpmHead.bpmConfidence < settings.bpmMinConfidence
                if (weakHead && seekable && hasFiniteDuration && duration > settings.bpmScanMs * 1.5 && sub.playingTrack === clone) {
                    val candidateStartMs = minOf(duration / 4, 60_000L).coerceAtLeast(settings.bpmScanMs)
                    val target = maxOf(candidateStartMs, lead).coerceAtLeast(0L)
                    if (target + 10_000L < duration) {
                        retryStartMs = target
                        retryFrames = seekAndCollect(
                            sub,
                            clone,
                            tap,
                            target,
                            msToSamplesLong(settings.bpmScanMs + 2000L),
                            deadline,
                            pumpFrame,
                        ).frames
                    }
                }
            }

            var tail = 0L
            var tailStartMs = 0L
            var tailFrames: List<Array<FloatArray>> = emptyList()
            var eofReached = false
            var actualDurationMs = duration

            val needTail = seekable && hasFiniteDuration && (settings.silence || settings.bpm)
            if (needTail && !analysisBroken && sub.playingTrack === clone) {
                val tailZoneStartMs = earliestTailSeekMs(duration, settings)
                if (tailZoneStartMs > 0L) {
                    val tailSeek = seekAndCollect(
                        sub,
                        clone,
                        tap,
                        tailZoneStartMs,
                        Long.MAX_VALUE,
                        deadline,
                        pumpFrame,
                    )
                    eofReached = tailSeek.eofReached
                    val tailEndMs = tailZoneStartMs + tailSeek.samples * 1000L / SAMPLE_RATE
                    if (eofReached && tailEndMs >= duration - METADATA_EOF_DRIFT_MS) {
                        actualDurationMs = tailEndMs
                    }
                    val tailInfo = extractTailFromCollected(tailSeek.frames, tailZoneStartMs, duration, settings)
                    tail = tailInfo.trailMs
                    tailStartMs = tailInfo.bpmTailStartMs
                    tailFrames = tailInfo.bpmTailFrames
                } else {
                    // Track fits inside head/single tail zone
                    tailStartMs = 0L
                }
            } else if (!needTail && !analysisBroken && finishedScanTrack === clone) {
                eofReached = true
            }

            return finalizeAnalysisResult(
                duration = duration,
                effectiveDurationMs = actualDurationMs,
                seekable = seekable,
                hasFiniteDuration = hasFiniteDuration,
                eofReached = eofReached,
                settings = settings,
                lead = lead,
                tail = tail,
                loudness = null,
                headFrames = headFrames,
                precomputedBpm = headResult,
                precomputedBpmHead = bpmHead,
                precomputedFeaturesHead = featuresHead,
                precomputedKey = key,
                retryStartMs = retryStartMs,
                retryFrames = retryFrames,
                tailStartMs = tailStartMs,
                tailFrames = tailFrames,
            )
        } finally {
            factory.removeTap(tap)
        }
    }

    private data class SeekCollectResult(
        val frames: List<Array<FloatArray>>,
        val samples: Long,
        val eofReached: Boolean,
    )

    private fun seekAndCollect(
        sub: AudioPlayer,
        clone: AudioTrack,
        tap: SiphonQueue,
        targetMs: Long,
        capSamples: Long,
        deadline: Long,
        pumpFrame: MutableAudioFrame,
    ): SeekCollectResult {
        val beforeSeekEpoch = tap.currentSeekEpoch()
        val posBefore = try { clone.position } catch (_: Exception) { 0L }
        try {
            clone.position = targetMs
        } catch (e: Exception) {
            log.warn("scan seek failed on guild {}", guildId, e)
            return SeekCollectResult(emptyList(), 0L, false)
        }

        // Wait until the decoder processes the seek (SiphonFilter.seekPerformed clears pre-seek PCM).
        val sinkTrash = Array(2) { FloatArray(1920) }
        var seekReady = false
        while (System.currentTimeMillis() < deadline && !analysisBroken) {
            if (tap.currentSeekEpoch() > beforeSeekEpoch) {
                seekReady = true
                break
            }
            if (sub.playingTrack !== clone) break
            while (tap.pendingFrames > 0) {
                if (tap.take(1920, 2, sinkTrash) <= 0) break
            }
            val providedTimecode = pumpSub(sub, pumpFrame)
            if (tap.currentSeekEpoch() > beforeSeekEpoch) {
                seekReady = true
                break
            }
            if (providedTimecode != null && targetMs > posBefore + 5_000L && providedTimecode >= targetMs - 3_000L) {
                tap.clear()
                seekReady = true
                break
            }
        }
        if (!seekReady && tap.currentSeekEpoch() <= beforeSeekEpoch) {
            return SeekCollectResult(emptyList(), 0L, false)
        }

        val frames = mutableListOf<Array<FloatArray>>()
        var collected = 0L
        while (collected < capSamples && System.currentTimeMillis() < deadline && !analysisBroken) {
            if (tap.pendingFrames == 0) {
                if (sub.playingTrack !== clone) break
                pumpSub(sub, pumpFrame)
                continue
            }
            while (tap.pendingFrames > 0 && collected < capSamples) {
                val n = minOf(1920L, capSamples - collected).toInt()
                val chunk = Array(2) { FloatArray(n) }
                val took = tap.take(n, 2, chunk)
                if (took <= 0) break
                frames.add(if (took < n) Array(2) { c -> chunk[c].copyOf(took) } else chunk)
                collected += took
            }
        }
        val eof = !analysisBroken &&
            (finishedScanTrack === clone || (sub.playingTrack !== clone && tap.pendingFrames == 0))
        return SeekCollectResult(frames, collected, eof)
    }

    private fun earliestTailSeekMs(duration: Long, settings: TrackAnalysisSettings): Long {
        val silenceNeed = if (settings.silence) settings.silenceTailScanMs else 0L
        val bpmNeed = if (settings.bpm) settings.bpmScanMs + 2000L else 0L
        return (duration - silenceNeed - bpmNeed).coerceAtLeast(0L)
    }

    private data class TailExtract(
        val trailMs: Long,
        val bpmTailStartMs: Long,
        val bpmTailFrames: List<Array<FloatArray>>,
    )

    private fun extractTailFromCollected(
        collected: List<Array<FloatArray>>,
        collectedStartMs: Long,
        effectiveDurationMs: Long,
        settings: TrackAnalysisSettings,
    ): TailExtract {
        if (collected.isEmpty()) return TailExtract(0L, 0L, emptyList())
        var totalSamples = 0L
        for (f in collected) totalSamples += f[0].size
        var trail = 0L
        if (settings.silence) {
            val silenceSamplesNeeded = msToSamplesLong(settings.silenceTailScanMs)
            val skipSamples = (totalSamples - silenceSamplesNeeded).coerceAtLeast(0L)
            val silenceFrames = sliceFrames(collected, skipSamples, totalSamples)
            trail = trailingSilenceMs(
                silenceFrames,
                settings.silenceThresholdDb,
                msToSamples(settings.silenceMinSoundMs),
            )
        }
        var tailStartMs = 0L
        var bpmTailFrames: List<Array<FloatArray>> = emptyList()
        if (settings.bpm) {
            val contentEndMs = if (trail > 0) (effectiveDurationMs - trail).coerceAtLeast(0L) else effectiveDurationMs
            tailStartMs = (contentEndMs - settings.bpmScanMs).coerceAtLeast(0L)
            if (tailStartMs > 0L) {
                val relStartSamples = msToSamplesLong((tailStartMs - collectedStartMs).coerceAtLeast(0L))
                val relEndSamples = minOf(totalSamples, relStartSamples + msToSamplesLong(settings.bpmScanMs + 2000L))
                bpmTailFrames = sliceFrames(collected, relStartSamples, relEndSamples)
            }
        }
        return TailExtract(trail, tailStartMs, bpmTailFrames)
    }

    private fun sliceFrames(
        frames: List<Array<FloatArray>>,
        startSample: Long,
        endSample: Long,
    ): List<Array<FloatArray>> {
        if (frames.isEmpty() || endSample <= startSample) return emptyList()
        val out = mutableListOf<Array<FloatArray>>()
        var pos = 0L
        for (frame in frames) {
            val len = frame[0].size
            val fEnd = pos + len
            if (fEnd > startSample && pos < endSample) {
                val from = maxOf(0L, startSample - pos).toInt()
                val to = minOf(len.toLong(), endSample - pos).toInt()
                if (from < to) {
                    out.add(if (from == 0 && to == len) frame else Array(frame.size) { c -> frame[c].copyOfRange(from, to) })
                }
            }
            pos = fEnd
            if (pos >= endSample) break
        }
        return out
    }

    private fun finalizeAnalysisResult(
        duration: Long,
        effectiveDurationMs: Long,
        seekable: Boolean,
        hasFiniteDuration: Boolean,
        eofReached: Boolean,
        settings: TrackAnalysisSettings,
        lead: Long,
        tail: Long,
        loudness: Float?,
        headFrames: List<Array<FloatArray>>,
        precomputedBpm: BpmDetector.BpmResult?,
        precomputedBpmHead: ExtendedBeatGrid?,
        precomputedFeaturesHead: TransitionFeatures?,
        precomputedKey: KeyAnalysis?,
        retryStartMs: Long,
        retryFrames: List<Array<FloatArray>>,
        tailStartMs: Long,
        tailFrames: List<Array<FloatArray>>,
    ): TrackAnalysisResult {
        var bpm = precomputedBpm
        var bpmHead = precomputedBpmHead
        var featuresHead = precomputedFeaturesHead
        var bpmTail: ExtendedBeatGrid? = null
        var featuresTail: TransitionFeatures? = null
        var key = precomputedKey

        if (settings.bpm) {
            val bpmHeadFrames = sliceFrames(headFrames, 0L, msToSamplesLong(settings.bpmScanMs))
            if (bpmHead == null && featuresHead == null && bpmHeadFrames.isNotEmpty()) {
                val eval = evaluateHeadBpm(bpmHeadFrames, settings, key)
                bpm = eval.bpm
                bpmHead = eval.grid
                featuresHead = eval.features
                key = eval.key
            }
            val headBeforeRetry = bpmHead
            val weakHead = headBeforeRetry == null || headBeforeRetry.bpmConfidence < settings.bpmMinConfidence
            if (weakHead && retryStartMs >= 0L && retryFrames.isNotEmpty()) {
                val retryGrid = BpmDetector.analyzeExtendedGrid(retryFrames, sampleRate = SAMPLE_RATE, windowStartMs = retryStartMs)
                if (retryGrid != null && retryGrid.periodMs.isFinite() && retryGrid.periodMs > 0 &&
                    (headBeforeRetry == null || retryGrid.bpmConfidence > headBeforeRetry.bpmConfidence)
                ) {
                    val periodMs = retryGrid.periodMs
                    val headPhaseMs = Math.round(((retryGrid.phaseMs % periodMs) + periodMs) % periodMs).toLong()
                    val periodSamples = retryGrid.periodSamples
                    val projectedPhaseSample = Math.round(((retryGrid.phaseSample.toDouble() % periodSamples) + periodSamples) % periodSamples)
                    val barSamples = periodSamples * 4.0
                    val projectedDownbeatSample = Math.round(((retryGrid.downbeatSample.toDouble() % barSamples) + barSamples) % barSamples)
                    bpmHead = retryGrid.copy(
                        phaseSample = projectedPhaseSample,
                        downbeatSample = projectedDownbeatSample,
                    )
                    val headBpm = bpm
                    if (headBpm == null || headBpm.confidence < bpmHead.bpmConfidence) {
                        bpm = BpmDetector.BpmResult(bpmHead.bpm, headPhaseMs, bpmHead.bpmConfidence)
                    }
                    if (featuresHead != null && bpmHeadFrames.isNotEmpty()) {
                        featuresHead = TransitionFeatureAnalyzer.analyze(bpmHeadFrames, grid = bpmHead)
                        bpmHead = mergePhraseConfidence(bpmHead, featuresHead)
                    }
                }
            }
            if (seekable && hasFiniteDuration) {
                if (tailStartMs == 0L) {
                    bpmTail = bpmHead
                    featuresTail = featuresHead
                } else if (tailFrames.isNotEmpty()) {
                    bpmTail = BpmDetector.analyzeExtendedGrid(tailFrames, sampleRate = SAMPLE_RATE, windowStartMs = tailStartMs)
                    featuresTail = TransitionFeatureAnalyzer.analyze(tailFrames, tailStartMs, bpmTail)
                    bpmTail = mergePhraseConfidence(bpmTail, featuresTail)
                }
            }
            val measuredTail = bpmTail
            val measuredHead = bpmHead
            val minConfidence = settings.bpmMinConfidence
            fun usable(grid: ExtendedBeatGrid?) = grid != null && grid.periodMs.isFinite() && grid.periodMs > 0
            fun weak(grid: ExtendedBeatGrid?) = grid == null || grid.bpmConfidence < minConfidence
            if (measuredHead !== measuredTail && weak(measuredHead) && usable(measuredTail) && !weak(measuredTail)) {
                val (grid, features) = fillMissingEnd(bpmHeadFrames, 0L, measuredTail!!)
                if (measuredHead == null || grid.bpmConfidence > measuredHead.bpmConfidence) {
                    bpmHead = grid
                    val headBpm = bpm
                    if (headBpm == null || headBpm.confidence < grid.bpmConfidence) {
                        bpm = BpmDetector.BpmResult(grid.bpm, grid.phaseMs, grid.bpmConfidence)
                    }
                    if (features != null) featuresHead = features
                }
            } else if (measuredHead !== measuredTail && weak(measuredTail) && usable(measuredHead) && !weak(measuredHead)) {
                val (grid, features) = fillMissingEnd(tailFrames, tailStartMs, measuredHead!!)
                if (measuredTail == null || grid.bpmConfidence > measuredTail.bpmConfidence) {
                    bpmTail = grid
                    if (features != null) featuresTail = features
                }
            } else if (measuredHead == null && usable(measuredTail)) {
                val (grid, features) = fillMissingEnd(bpmHeadFrames, 0L, measuredTail!!)
                bpmHead = grid
                if (bpm == null) bpm = BpmDetector.BpmResult(grid.bpm, grid.phaseMs, grid.bpmConfidence)
                if (features != null) featuresHead = features
            } else if (measuredTail == null && measuredHead != null) {
                val (grid, features) = fillMissingEnd(tailFrames, tailStartMs, measuredHead)
                bpmTail = grid
                if (features != null) featuresTail = features
            }
        }
        if (settings.key && key == null && headFrames.isNotEmpty()) {
            key = KeyDetector.analyze(headFrames, msToSamples(settings.keyScanMs))
        }
        val expectedDuration = if (eofReached && effectiveDurationMs > 0L) effectiveDurationMs else duration
        val keyIncomplete = key != null && expectedDuration > 0 &&
            key.windowEndMs < minOf(expectedDuration, settings.keyScanMs) - 250
        val guardedLead = if (duration > 0 && lead >= duration - 500) 0 else lead.coerceAtLeast(0)
        val trim = if (settings.silence) {
            TrimPoints(guardedLead, tail.coerceAtLeast(0))
        } else {
            null
        }
        if (hasFiniteDuration) {
            featuresHead = featuresHead?.let { guardFeatureWindow(it, minOf(expectedDuration, settings.bpmScanMs), eofReached) }
            val tailEndMs = (expectedDuration - tail).coerceAtLeast(0L)
            featuresTail = featuresTail?.let { guardFeatureWindow(it, tailEndMs, eofReached) }
        }
        return TrackAnalysisResult(
            trim = trim,
            loudnessRms = loudness,
            bpm = bpm,
            aborted = analysisBroken || keyIncomplete,
            bpmHead = bpmHead,
            bpmTail = bpmTail,
            featuresHead = featuresHead,
            featuresTail = featuresTail,
            key = key,
        )
    }

    private fun startScanTrack(sub: AudioPlayer, track: AudioTrack): AudioTrack {
        val clone = track.makeClone()
        finishedScanTrack = null
        activeScanTrack = clone
        sub.playTrack(clone)
        return clone
    }

    private fun guardFeatureWindow(
        features: TransitionFeatures,
        expectedEndMs: Long,
        eofReached: Boolean = false,
    ): TransitionFeatures {
        if (analysisBroken) return features.copy(unknownReason = "incomplete_window")
        if (eofReached && features.windowEndMs >= expectedEndMs - METADATA_EOF_DRIFT_MS) return features
        return if (features.windowEndMs < expectedEndMs - 250) features.copy(unknownReason = "incomplete_window") else features
    }

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
        player.addListener(object : AudioEventAdapter() {
            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                if (track !== activeScanTrack) return
                when (endReason) {
                    AudioTrackEndReason.FINISHED -> finishedScanTrack = track
                    AudioTrackEndReason.LOAD_FAILED -> analysisBroken = true
                    else -> Unit
                }
            }

            override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
                if (track === activeScanTrack) {
                    analysisBroken = true
                }
            }
        })
        analysisPlayer = player
        analysisFactory = factory
        player to factory
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
            finishedScanTrack = null
            activeScanTrack = clone
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
            activeScanTrack = null
            stopTrack()
            return frames
        } finally {
            factory.removeTap(tap)
        }
    }

    /**
     * Pulls decoded frames as fast as the analysis decoder produces them. The
     * timed provide parks the thread while the source buffers (network reads),
     * so there is no fixed pacing sleep; the old 8-frames-per-10ms pacing capped
     * analysis at about 16x realtime (15s for a 4-minute track).
     *
     * @return timecode (ms) of the last provided frame if any frame was pulled.
     */
    private fun pumpSub(sub: AudioPlayer, frame: MutableAudioFrame): Long? {
        var burst = 0
        var lastTimecode: Long? = null
        while (burst < ANALYSIS_PUMP_BURST && !analysisBroken) {
            try {
                if (!sub.provide(frame, ANALYSIS_PROVIDE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                lastTimecode = frame.timecode
            } catch (e: java.util.concurrent.TimeoutException) {
                break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                analysisBroken = true
                break
            } catch (e: Exception) {
                analysisBroken = true
                log.warn("analysis pump failed on guild {}", guildId, e)
                break
            }
            burst++
        }
        return lastTimecode
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

    private fun msToSamplesLong(ms: Long): Long = (ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000L

    companion object {
        private val log = LoggerFactory.getLogger(TrackAnalysisService::class.java)
        private const val SAMPLE_RATE = 48000
        /** Tracks up to 10 minutes run a single sequential pass (1 InnerTube + 1 CDN GET). */
        const val SEQUENTIAL_MAX_MS = 10 * 60_000L
        /** Container metadata duration vs decoded PCM EOF slack when EOF was cleanly reached. */
        private const val METADATA_EOF_DRIFT_MS = 2_000L
        /** Relative tempo band searched when re-measuring a missing head/tail grid. */
        private const val MISSING_END_BPM_BAND = 0.04f
        private const val SCAN_TIMEOUT_MS = 45_000L
        private const val LOUDNESS_GRACE_MS = 5_000L
        /** Audio decoded before a provisional loudness is reported; matters most for network-bound sources. */
        const val PROVISIONAL_LOUDNESS_MS = 8_000L
        /**
         * Frames pulled before the caller drains the tap: 2.56s of audio, well inside
         * the tap's 512-frame and 8s sample bounds so a burst never overflows.
         */
        private const val ANALYSIS_PUMP_BURST = 128
        private const val ANALYSIS_PROVIDE_WAIT_MS = 20L
    }
}
