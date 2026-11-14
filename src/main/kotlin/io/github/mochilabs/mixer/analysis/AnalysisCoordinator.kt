package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

/** What [AnalysisCoordinator] reads from and reports back to its guild mixer. */
internal interface AnalysisHost {
    val guildId: Long
    /** The guild state lock; every `...Locked` member here must be called while holding it. */
    val lock: Any

    val normalizationEnabled: Boolean
    val normGainBaseline: Float
    val silenceSkipEnabled: Boolean
    val silenceThresholdDb: Float
    val silenceMinSoundMs: Long
    val silenceHeadScanMs: Long
    val silenceTailScanMs: Long
    val bpmDetectionEnabled: Boolean
    val bpmScanMs: Long
    val beatmatchEnabled: Boolean
    val beatmatchMinConfidence: Float
    val keyDetectionEnabled: Boolean
    val keyScanMs: Long

    val playingTrack: AudioTrack?
    /** Pending slot first, then ready. */
    fun queuedTracksLocked(): List<AudioTrack>

    fun promoteIfPendingLocked(expectedId: String? = null)
    /** Takes [lock] itself. */
    fun applyAnalyzedNormGain(identifier: String)
}

/**
 * Schedules a guild's analysis scans and writes their results to [AnalysisCaches].
 *
 * One scan runs at a time per guild on the isolated analysis player. Queue slots
 * and gains stay with the host; this class reaches them only through [AnalysisHost].
 */
internal class AnalysisCoordinator(
    private val host: AnalysisHost,
    playerManager: AudioPlayerManager,
    private val analysisExecutor: Executor,
) {
    private val service = TrackAnalysisService(playerManager, host.guildId)
    private val state = AnalysisState()

    private data class DeferredWarmup(
        val track: AudioTrack,
        val includeNormalization: Boolean,
    )

    private var queued: Boolean = false
    /** Latest cache-only request waiting for the guild's single analysis player. */
    private var deferredWarmup: DeferredWarmup? = null

    /** True while a scan owns the analysis player. */
    val activeLocked: Boolean get() = state.active

    /** Drops the waiting warmup and the active flag when the guild mixer is destroyed. */
    fun resetLocked() {
        deferredWarmup = null
        state.active = false
    }

    fun destroy() = service.destroy()

    /**
     * Schedules a background loudness scan of the currently playing track so
     * its measured LUFS lands in the shared cache (and, if it is still the
     * playing track, its normalization gain ramps to it). Runs on the
     * dedicated analysis decoder, never the live sub player.
     */
    fun scheduleBaselineAnalysis(track: AudioTrack) {
        synchronized(host.lock) {
            if (host.normGainBaseline != 0f || state.active) return
            state.active = true
            service.beginScan()
        }
        analysisExecutor.execute {
            val clone = try {
                track.makeClone()
            } catch (e: Exception) {
                synchronized(host.lock) { state.active = false }
                drainDeferredWarmup()
                return@execute
            }
            try {
                val trackId = idOf(track) ?: track.identifier
                val loudness = service.measureBaseline(clone) { noteProvisionalLoudness(trackId, it) }
                if (loudness != null) {
                    AnalysisCaches.norm[trackId] = loudness
                    host.applyAnalyzedNormGain(trackId)
                    log.info("normalization baseline guild {}: lufs={}", host.guildId, loudness)
                }
            } catch (e: Exception) {
                log.warn("baseline loudness scan failed on guild {}", host.guildId, e)
            } finally {
                synchronized(host.lock) { state.active = false }
                drainDeferredWarmup()
            }
        }
    }

    fun maybeRetryAnalysis(): AudioTrack? {
        val now = System.currentTimeMillis()
        return synchronized(host.lock) {
            if (queued) return null
            val mainPlaying = host.playingTrack
            val candidates = host.queuedTracksLocked() + listOfNotNull(
                if (host.bpmDetectionEnabled || host.keyDetectionEnabled || host.silenceSkipEnabled) mainPlaying else null,
            )
            val pending = candidates.firstOrNull { candidate ->
                if (!candidate.isSeekable || candidate.duration <= 0) return@firstOrNull false
                val id = idOf(candidate) ?: return@firstOrNull false
                val needSilence = host.silenceSkipEnabled && !AnalysisCaches.trim.contains(id)
                val needNorm = candidate !== mainPlaying && host.normalizationEnabled && !AnalysisCaches.norm.contains(id)
                val needBpm = host.bpmDetectionEnabled && needsBpmAnalysisLocked(id)
                val needKey = host.keyDetectionEnabled && !AnalysisCaches.key.contains(id)
                (needSilence || needNorm || needBpm || needKey) && state.canRetryLocked(id, now)
            } ?: return null
            log.debug("retrying deferred analysis for '{}' on guild {}", titleOf(pending), host.guildId)
            try {
                pending.makeClone()
            } catch (e: Exception) {
                log.debug("analysis retry clone failed on guild {}: {}", host.guildId, e.toString())
                null
            }
        }
    }

    fun shouldWarmNormFor(track: AudioTrack): Boolean {
        if (!host.normalizationEnabled || (!host.silenceSkipEnabled && !host.bpmDetectionEnabled)) return false
        val duration = try {
            track.duration
        } catch (_: Exception) {
            -1L
        }
        val seekable = try {
            track.isSeekable
        } catch (_: Exception) {
            false
        }
        return seekable && duration in 1..TrackAnalysisService.SEQUENTIAL_MAX_MS
    }

    /** Bounded, cache-only warmup; it never arms a queue slot or mutates playback. */
    fun requestPreanalysis(
        track: AudioTrack,
        includeNormalization: Boolean = shouldWarmNormFor(track),
    ): Boolean {
        val id = idOf(track) ?: return false
        val includeSilence = host.silenceSkipEnabled
        val warmNorm = includeNormalization && host.normalizationEnabled && host.normGainBaseline == 0f
        synchronized(host.lock) {
            val needSilence = includeSilence && !AnalysisCaches.trim.contains(id)
            val needNorm = warmNorm && !AnalysisCaches.norm.contains(id)
            val needBpm = host.bpmDetectionEnabled && needsBpmAnalysisLocked(id)
            val needKey = host.keyDetectionEnabled && !AnalysisCaches.key.contains(id)
            if ((!needSilence && !needNorm && !needBpm && !needKey) || !track.isSeekable || track.duration <= 0) return false
            if (queued || state.active) {
                deferredWarmup = DeferredWarmup(track, warmNorm)
                return true
            }
            queued = true
            deferredWarmup = null
        }
        return scheduleWarmup(track, warmNorm)
    }

    private fun scheduleWarmup(track: AudioTrack, includeNormalization: Boolean): Boolean {
        return try {
            analysisExecutor.execute {
                try {
                    analyzeBlocking(
                        track,
                        cacheOnly = true,
                        includeSilence = host.silenceSkipEnabled,
                        includeNormalization = includeNormalization,
                    )
                }
                catch (e: Exception) { log.warn("preanalysis failed on guild {}", host.guildId, e) }
                finally {
                    synchronized(host.lock) { queued = false }
                    drainDeferredWarmup()
                }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            synchronized(host.lock) { queued = false }
            false
        }
    }

    private fun drainDeferredWarmup() {
        val next = synchronized(host.lock) {
            if (queued || state.active) return
            deferredWarmup?.also {
                deferredWarmup = null
                queued = true
            }
        }
        if (next != null) scheduleWarmup(next.track, next.includeNormalization)
    }

    private fun needsBpmAnalysisLocked(identifier: String): Boolean =
        state.completedBpmIdentifier != identifier &&
            !(AnalysisCaches.bpm.contains(identifier) && AnalysisCaches.bpmHead.contains(identifier) && AnalysisCaches.bpmTail.contains(identifier) && AnalysisCaches.featuresHead.contains(identifier) && AnalysisCaches.featuresTail.contains(identifier))

    /**
     * True once the incoming track's beatmatching inputs (BPM grid + transition
     * features) are cached, or when beatmatching never needs them. A late arm
     * can leave analysis running while the fade window opens; the poll loop
     * uses this to wait for the scan instead of firing a plain fade with
     * `missing_features`.
     */
    fun incomingFeaturesReadyLocked(track: AudioTrack): Boolean {
        if (!host.beatmatchEnabled || !host.bpmDetectionEnabled) return true
        val id = idOf(track) ?: return true
        return !needsBpmAnalysisLocked(id)
    }

    /** Scans a clone on the isolated analysis player. A concurrent scan defers this work. */
    fun analyzeBlocking(
        track: AudioTrack,
        cacheOnly: Boolean = false,
        includeSilence: Boolean = !cacheOnly,
        includeNormalization: Boolean = !cacheOnly,
    ) {
        val doSilence = host.silenceSkipEnabled && includeSilence
        val doNorm = host.normalizationEnabled && includeNormalization
        val doBpm = host.bpmDetectionEnabled
        val doKey = host.keyDetectionEnabled && track.isSeekable && track.duration in 1 until Long.MAX_VALUE / 2
        if (!doSilence && !doNorm && !doBpm && !doKey) {
            if (!cacheOnly) synchronized(host.lock) { host.promoteIfPendingLocked() }
            return
        }
        val identifier = idOf(track) ?: run {
            if (!cacheOnly) synchronized(host.lock) { host.promoteIfPendingLocked() }
            return
        }
        synchronized(host.lock) {
            val hasSilence = !doSilence || AnalysisCaches.trim.contains(identifier)
            val hasNorm = !doNorm || AnalysisCaches.norm.contains(identifier)
            val hasBpm = !doBpm || !needsBpmAnalysisLocked(identifier)
            val hasKey = !doKey || AnalysisCaches.key.contains(identifier)
            if (hasSilence && hasNorm && hasBpm && hasKey) {
                if (!cacheOnly) host.promoteIfPendingLocked(identifier)
                return
            }
            if (state.active) {
                // A queued cache-only warmup may reach this gate after a normal scan starts.
                // Retain it; the scan owner or warmup worker will drain it when idle.
                if (cacheOnly && deferredWarmup == null) {
                    deferredWarmup = DeferredWarmup(track, includeNormalization)
                }
                log.debug(
                    "analysis deferred on guild {}: another scan is running; will retry",
                    host.guildId,
                )
                return
            }
            state.active = true
            service.beginScan()
        }
        try {
            val hasSilence = doSilence && !AnalysisCaches.trim.contains(identifier)
            val hasNorm = doNorm && !AnalysisCaches.norm.contains(identifier)
            val needBpm = doBpm && synchronized(host.lock) { needsBpmAnalysisLocked(identifier) }
            val needKey = doKey && !AnalysisCaches.key.contains(identifier)
            val result = service.analyze(
                track,
                onLoudnessEstimate = if (hasNorm) { lufs -> noteProvisionalLoudness(identifier, lufs) } else null,
                settings = TrackAnalysisSettings(
                    silence = hasSilence,
                    normalization = hasNorm,
                    bpm = needBpm,
                    silenceThresholdDb = host.silenceThresholdDb,
                    silenceMinSoundMs = host.silenceMinSoundMs,
                    silenceHeadScanMs = host.silenceHeadScanMs,
                    silenceTailScanMs = host.silenceTailScanMs,
                    bpmScanMs = host.bpmScanMs,
                    key = needKey,
                    keyScanMs = host.keyScanMs,
                    bpmMinConfidence = host.beatmatchMinConfidence,
                ),
            )
            if (result.aborted || result.featuresHead?.unknownReason == "incomplete_window" ||
                result.featuresTail?.unknownReason == "incomplete_window") {
                synchronized(host.lock) { state.noteFailureLocked(identifier) }
                return
            }
            if (hasNorm && result.loudnessRms != null) {
                AnalysisCaches.norm[identifier] = result.loudnessRms
                host.applyAnalyzedNormGain(identifier)
                log.info("normalization guild {}: lufs={}", host.guildId, result.loudnessRms)
            }
            if (needKey && result.key != null) AnalysisCaches.key[identifier] = result.key
            if (needBpm && result.bpm != null) {
                AnalysisCaches.bpm[identifier] = result.bpm
                log.info(
                    "bpm analysis guild {}: bpm={} firstBeat={}ms confidence={}",
                    host.guildId,
                    result.bpm.bpm,
                    result.bpm.firstBeatMs,
                    result.bpm.confidence,
                )
            }
            if (needBpm) {
                result.featuresHead?.let { AnalysisCaches.featuresHead[identifier] = it }
                result.featuresTail?.let { AnalysisCaches.featuresTail[identifier] = it }
            }
            if (needBpm && result.bpmHead != null) {
                AnalysisCaches.bpmHead[identifier] = result.bpmHead
            }
            if (needBpm && result.bpmTail != null) {
                AnalysisCaches.bpmTail[identifier] = result.bpmTail
            }
            // The playing track's loudness is backfilled even by cache-only warmups: a busy
            // slot can drop its own deferred baseline scan when a later warmup replaces it.
            backfillPlayingTrack(identifier, doSilence, host.normalizationEnabled, doBpm, doKey)
            synchronized(host.lock) {
                if (needBpm) state.completedBpmIdentifier = identifier
                if (hasSilence) {
                    AnalysisCaches.trim[identifier] = result.trim ?: TrimPoints.NONE
                }
                state.clearFailureIfMatchingLocked(identifier)
                if (!cacheOnly) host.promoteIfPendingLocked(identifier)
            }
            if (hasSilence) {
                log.info(
                    "silence analysis guild {}: lead={}ms trail={}ms",
                    host.guildId,
                    result.trim?.leadMs ?: 0,
                    result.trim?.trailMs ?: 0,
                )
            }
        } catch (e: Exception) {
            log.warn("silence analysis failed on guild {}", host.guildId, e)
            synchronized(host.lock) { state.noteFailureLocked(identifier) }
        } finally {
            synchronized(host.lock) { state.active = false }
            drainDeferredWarmup()
        }
    }

    /**
     * Fills any missing measurements on the currently playing main track using
     * at most one analysis pass, rather than separate tail/norm/silence opens.
     */
    private fun backfillPlayingTrack(
        analyzedIdentifier: String,
        doSilence: Boolean,
        doNorm: Boolean,
        doBpm: Boolean,
        doKey: Boolean,
    ) {
        val playing = synchronized(host.lock) { host.playingTrack } ?: return
        val playingId = idOf(playing) ?: return
        val needPlayingBpm = doBpm && playingId != analyzedIdentifier &&
            !AnalysisCaches.featuresTail.contains(playingId)
        val needPlayingNorm = doNorm && playingId != analyzedIdentifier &&
            !AnalysisCaches.norm.contains(playingId) && host.normGainBaseline == 0f
        val needPlayingSilence = doSilence && playingId != analyzedIdentifier &&
            !AnalysisCaches.trim.contains(playingId)
        val needPlayingKey = doKey && playingId != analyzedIdentifier &&
            !AnalysisCaches.key.contains(playingId)
        if (!needPlayingBpm && !needPlayingNorm && !needPlayingSilence && !needPlayingKey) return

        val playingResult = service.analyze(
            playing,
            settings = TrackAnalysisSettings(
                silence = needPlayingSilence,
                normalization = needPlayingNorm,
                bpm = needPlayingBpm,
                silenceThresholdDb = host.silenceThresholdDb,
                silenceMinSoundMs = host.silenceMinSoundMs,
                silenceHeadScanMs = host.silenceHeadScanMs,
                silenceTailScanMs = host.silenceTailScanMs,
                bpmScanMs = host.bpmScanMs,
                key = needPlayingKey,
                keyScanMs = host.keyScanMs,
                bpmMinConfidence = host.beatmatchMinConfidence,
            ),
            onLoudnessEstimate = if (needPlayingNorm) { lufs -> noteProvisionalLoudness(playingId, lufs) } else null,
        )
        if (playingResult.aborted) return

        if (needPlayingNorm && playingResult.loudnessRms != null) {
            AnalysisCaches.norm[playingId] = playingResult.loudnessRms
            host.applyAnalyzedNormGain(playingId)
            log.info("normalization guild {} (playing track): lufs={}", host.guildId, playingResult.loudnessRms)
        }
        if (needPlayingSilence && playingResult.trim != null) {
            AnalysisCaches.trim[playingId] = playingResult.trim
        }
        if (needPlayingKey && playingResult.key != null) {
            AnalysisCaches.key[playingId] = playingResult.key
        }
        if (needPlayingBpm && playingResult.featuresTail?.unknownReason != "incomplete_window") {
            playingResult.bpm?.let { if (!AnalysisCaches.bpm.contains(playingId)) AnalysisCaches.bpm[playingId] = it }
            playingResult.bpmHead?.let { if (!AnalysisCaches.bpmHead.contains(playingId)) AnalysisCaches.bpmHead[playingId] = it }
            playingResult.featuresHead?.let { if (!AnalysisCaches.featuresHead.contains(playingId)) AnalysisCaches.featuresHead[playingId] = it }
            playingResult.bpmTail?.let { AnalysisCaches.bpmTail[playingId] = it }
            playingResult.featuresTail?.let { AnalysisCaches.featuresTail[playingId] = it }
        }
    }

    /** Provisional loudness from a scan's opening seconds; ignored once the full value exists. */
    fun noteProvisionalLoudness(identifier: String, lufs: Float) {
        if (AnalysisCaches.norm.contains(identifier)) return
        AnalysisCaches.normProvisional[identifier] = lufs
        host.applyAnalyzedNormGain(identifier)
        log.debug("provisional normalization guild {}: lufs={}", host.guildId, lufs)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AnalysisCoordinator::class.java)
    }
}
