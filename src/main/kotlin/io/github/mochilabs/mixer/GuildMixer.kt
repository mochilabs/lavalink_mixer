package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-guild mixing state. Owns:
 * - a gapless "next track" slot, preloaded via [MixerPlugin.preloadNext],
 * - an overlap crossfade (secondary player decoding the next track while the
 *   main player's tail fades out),
 * - TTS/announcement overlays with music ducking.
 *
 * The secondary player never touches the voice connection; its PCM is
 * siphoned into [siphon] and mixed into the main chain by [MixerFilter].
 * A shared scheduler in [MixerPlugin] calls [drain] (~50Hz, keeps the
 * secondary decoder flowing) and [poll] (~10Hz, crossfade trigger).
 *
 * All mutable state is guarded by [lock]; [advance] is called from the voice
 * thread and only snapshots gains.
 */
class GuildMixer(
    val guildId: Long,
    private val playerManager: AudioPlayerManager,
    config: MixerConfig,
    private val analysisExecutor: Executor,
) {
    private val lock = Any()
    /** Outer lock serializing main.play/main.stop across doHandoff, advanceNext, and override. Never acquired while holding [lock]. */
    private val playerMutationLock = Any()
    @Volatile var main: IPlayer? = null
        private set

    @Volatile var crossfadeEnabled: Boolean = config.crossfadeEnabled
    @Volatile var crossfadeMs: Long = config.crossfadeMs
    @Volatile var fadeInMs: Long = config.fadeInMs
    @Volatile var duckLevel: Float = config.duckLevel
    @Volatile var duckFadeMs: Long = config.duckFadeMs
    @Volatile var maskMs: Long = config.maskMs
    @Volatile var maxParallelOverlays: Int = config.maxParallelOverlays.coerceIn(1, 16)

    @Volatile var normalizationEnabled: Boolean = config.normalizationEnabled
    @Volatile var targetLufs: Float = config.targetLufs

    fun updateNormalization(enabled: Boolean, newTargetLufs: Float? = null) {
        var needsBaselineTrack: AudioTrack? = null
        synchronized(lock) {
            normalizationEnabled = enabled
            newTargetLufs?.let { targetLufs = it.coerceIn(-30.0f, -6.0f) }
            if (!enabled) {
                mainNormGain = 1f
                subNormGain = 1f
                return
            }
            val playing = main?.audioPlayer?.playingTrack
            if (playing != null) {
                mainNormGain = normGainForLocked(playing)
                val id = idOf(playing)
                if (normGainBaseline == 0f &&
                    !analysisState.active &&
                    readyNextTrack == null &&
                    pendingNextTrack == null &&
                    (id == null || !AnalysisCaches.norm.contains(id))
                ) {
                    needsBaselineTrack = playing
                }
            }
            val activeSub = subTrack
            if (xfadeActive && activeSub != null) {
                subNormGain = normGainForLocked(activeSub)
            }
        }
        needsBaselineTrack?.let { scheduleBaselineAnalysis(it) }
    }

    fun updateBpm(
        enabled: Boolean,
        scanMs: Long? = null,
        beatmatchEnabled: Boolean? = null,
        maxTempoChange: Float? = null,
        minConfidence: Float? = null,
    ) {
        synchronized(lock) {
            bpmDetectionEnabled = enabled
            scanMs?.let { bpmScanMs = it.coerceIn(5000, 60000) }
            beatmatchEnabled?.let { this.beatmatchEnabled = it }
            maxTempoChange?.let { this.maxTempoChange = it.coerceIn(0f, 0.12f) }
            minConfidence?.let { this.beatmatchMinConfidence = it.coerceIn(0f, 1f) }
        }
    }

    fun updateBeatmatch(enabled: Boolean, maxTempoChange: Float? = null, minConfidence: Float? = null) {
        synchronized(lock) {
            beatmatchEnabled = enabled
            maxTempoChange?.let { this.maxTempoChange = it.coerceIn(0f, 0.12f) }
            minConfidence?.let { this.beatmatchMinConfidence = it.coerceIn(0f, 1f) }
        }
    }

    fun updateKey(enabled: Boolean, scanMs: Long? = null) {
        val playing = synchronized(lock) {
            keyDetectionEnabled = enabled
            scanMs?.let { keyScanMs = it.coerceIn(5000, 60000) }
            if (enabled) main?.audioPlayer?.playingTrack else null
        }
        playing?.let { track ->
            runCatching { track.makeClone() }.getOrNull()?.let { requestPreanalysis(it) }
        }
    }

    fun updateSilence(
        enabled: Boolean,
        thresholdDb: Float? = null,
        minSoundMs: Long? = null,
        headScanMs: Long? = null,
        tailScanMs: Long? = null,
        confirmMs: Long? = null,
    ) {
        val playing = synchronized(lock) {
            silenceSkipEnabled = enabled
            thresholdDb?.let { silenceThresholdDb = it.coerceIn(-90f, -20f) }
            minSoundMs?.let { silenceMinSoundMs = it.coerceIn(20, 2000) }
            headScanMs?.let { silenceHeadScanMs = it.coerceIn(1000, 60000) }
            tailScanMs?.let { silenceTailScanMs = it.coerceIn(1000, 60000) }
            confirmMs?.let { tailConfirmMs = it.coerceIn(300, 10000) }
            tailWatch = enabled && !protocol.preserveTransition
            if (!enabled) tailCutter.reset()
            if (enabled) main?.audioPlayer?.playingTrack else null
        }
        playing?.let { track ->
            runCatching { track.makeClone() }.getOrNull()?.let { requestPreanalysis(it) }
        }
    }

    @Volatile var silenceSkipEnabled: Boolean = config.silenceSkipEnabled
    @Volatile var silenceThresholdDb: Float = config.silenceThresholdDb
    @Volatile var silenceMinSoundMs: Long = config.silenceMinSoundMs
    @Volatile var silenceHeadScanMs: Long = config.silenceHeadScanMs
    @Volatile var silenceTailScanMs: Long = config.silenceTailScanMs
    /** Sustained realtime silence needed before an early cut. */
    @Volatile var tailConfirmMs: Long = config.tailConfirmMs
    /** Set by poll(): realtime tail watch armed (cheap per-chunk check reads this). */
    @Volatile var tailWatch: Boolean = config.silenceSkipEnabled
    /** Tempo (BPM) detection is opt-in; each preloaded track decodes one head window for onset analysis. */
    @Volatile var bpmDetectionEnabled: Boolean = config.bpmDetectionEnabled
    @Volatile var bpmScanMs: Long = config.bpmScanMs.coerceIn(5000, 60000)
    @Volatile var keyDetectionEnabled: Boolean = config.keyDetectionEnabled
    @Volatile var keyScanMs: Long = config.keyScanMs.coerceIn(5000, 60000)
    @Volatile var beatmatchEnabled: Boolean = config.beatmatchEnabled
    @Volatile var maxTempoChange: Float = config.maxTempoChange.coerceIn(0f, 0.12f)
    @Volatile var beatmatchMinConfidence: Float = config.beatmatchMinConfidence.coerceIn(0f, 1f)

    // ---- Protocol State ----
    private val protocol = ProtocolController(object : MixerCore {
        override val guildId: Long get() = this@GuildMixer.guildId
        override val lock: Any get() = this@GuildMixer.lock
        override val playerMutationLock: Any get() = this@GuildMixer.playerMutationLock
        override val playerManager: AudioPlayerManager get() = this@GuildMixer.playerManager
        override val mainPlayer: IPlayer? get() = main
        override val playingTrack: AudioTrack? get() = main?.audioPlayer?.playingTrack
        override val crossfadeMs: Long get() = this@GuildMixer.crossfadeMs
        override val prefetchLeadMs: Long get() = if (bpmDetectionEnabled && beatmatchEnabled) {
            (bpmScanMs * 2 + 7000L).coerceAtMost(120_000L)
        } else 7000L
        override val isXfadeActive: Boolean get() = xfadeActive
        override val isOverlayActive: Boolean get() = overlayActive

        override fun safePosition(track: AudioTrack): Long = this@GuildMixer.safePosition(track)

        override fun clearNextLocked() {
            adaptiveTrim = null
            transitionStatus = TransitionStatus("none", null)
            clearNext()
        }

        override fun preloadArmedTrackLocked(track: AudioTrack) {
            readyNextTrack = null
            pendingNextTrack = track
            promoteIfPendingLocked()
            if (readyNextTrack == null && pendingNextTrack != null) {
                promoteForcedLocked()
            }
        }

        override fun scheduleArmedAnalysis(track: AudioTrack) {
            val cloneForAnalysis = runCatching { track.makeClone() }.getOrNull() ?: return
            analysisExecutor.execute {
                try {
                    analyzeBlocking(cloneForAnalysis)
                } catch (e: Exception) {
                    log.warn("silence analysis failed on guild {}", this@GuildMixer.guildId, e)
                }
            }
        }

        override fun clearMaskingLocked() {
            masking = false
            maskingTrack = null
        }

        override fun restoreUnityGainAfterStopLocked() {
            mainGain.rampTo(1f, msToSamples(duckFadeMs))
        }

        override fun syncPlayingStateOnResetLocked(playing: AudioTrack?) {
            if (playing != null) {
                syncChainPos(this@GuildMixer.safePosition(playing))
                if (normalizationEnabled) {
                    mainNormGain = normGainForLocked(playing)
                }
            }
        }

        override fun abortXfadeOutsideLock(reason: String) {
            this@GuildMixer.abortXfade(reason)
        }

        override fun cancelOverlayOutsideLock(): Boolean = this@GuildMixer.cancelOverlay()
        override fun notifyEvent(type: String, body: JsonObjectBuilder.() -> Unit) {
            notifyMixerEvent(type, body)
        }
    })

    val playbackState: PlaybackState
        get() = protocol.playbackState
    val lastKnownEpoch: Long
        get() = protocol.gate.lastKnownEpoch
    val windowOpenAt: Long
        get() = protocol.gate.windowOpenAt
    // ---- volume normalization ----

    /** Normalization gain for the currently playing main track; read lock-free by the voice thread. */
    @Volatile private var mainNormGain: Float = 1f
    /** Normalization gain for the armed sub (crossfade) track; 1 while no overlap is armed. */
    @Volatile private var subNormGain: Float = 1f
    /**
     * Test-only anchor: when non-zero, [normGainForLocked] normalizes every
     * track to this loudness instead of [targetLufs]. Production always
     * normalizes to [targetLufs] (-14 LUFS); see commit 1f80569 which removed
     * the first-track anchor in favor of a fixed target.
     */
    @Volatile private var normGainBaseline: Float = 0f

    private val tailCutter = TailCutDecider()

    private var pendingNextTrack: AudioTrack? = null
    private var slotRevision: Long = 0L
    private var analysisQueued: Boolean = false
    /** Latest cache-only request waiting for the guild's single analysis player. */
    private var deferredWarmup: AudioTrack? = null
    private var preservedStartTrack: AudioTrack? = null
    private data class PlannedTrim(val outgoing: AudioTrack, val incoming: AudioTrack, val decision: AdaptiveTransitionDecision.SilenceTrim)
    private var adaptiveTrim: PlannedTrim? = null
    private var plannedRecipe: TransitionRecipe? = null
    data class TransitionStatus(val kind: String, val reason: String?)
    @Volatile var transitionStatus = TransitionStatus("none", null)
        private set
    private var readyNextTrack: AudioTrack? = null
    private var attachedPlayers = mutableSetOf<AudioPlayer>()

    private var subPlayer: AudioPlayer? = null
    private var siphonFactory: SiphonFactory? = null
    private val analysisService = TrackAnalysisService(playerManager, guildId)
    private val analysisState = AnalysisState()
    private var subTrack: AudioTrack? = null
    private var subStartMs: Long = 0L
    /** Active parallel (`mode=parallel`) overlays: each owns its decoder + siphon + fade ramp. */
    private val parallelOverlays = LinkedHashMap<Long, OverlaySlot>()
    @Volatile private var parallelOverlaySnapshot: List<OverlaySlot> = emptyList()

    private fun publishParallelOverlaysLocked() {
        parallelOverlaySnapshot = parallelOverlays.values.toList()
    }
    /** Sequential (single-sub) overlay is still active; mutually exclusive with parallel overlays. */
    private var sequentialOverlayId: Long? = null
    @Volatile private var sequentialOverlayChannel: AnnounceChannel = AnnounceChannel.BOTH
    /** Voice thread reads these without taking [lock]; races only shift a fade by a chunk. */
    @Volatile private var overlayActive: Boolean = false
    @Volatile private var xfadeActive: Boolean = false
    @Volatile var activeRecipe: TransitionRecipe? = null
        private set

    fun setActiveRecipeForTest(
        recipe: TransitionRecipe?,
        startSample: Long = 0L,
        endSample: Long = 0L,
    ) {
        activeRecipe = recipe
        xfadeActive = (recipe != null)
        xfadeStartSample = startSample
        xfadeEndSample = endSample
        xfadeFadeEndSample = endSample
    }
    val currentXfadeSample: Long get() = if (xfadeStartSample >= 0L) (chainPos.get() - xfadeStartSample).coerceAtLeast(0L) else 0L

    private var xfadeHandoffMs: Long = -1L
    /** Main-track instance the armed overlap belongs to; a handoff must never fire after that track was replaced. */
    private var xfadeMainTrack: AudioTrack? = null
    /** Position within [subTrack] that has reached the audible output. */
    private val subOutputSamples = AtomicLong(0L)
    private val subSourceMilliSamples = AtomicLong(0L)
    @Volatile private var xfadeBeatMatched: Boolean = false
    @Volatile private var xfadeFallbackReason: String? = null
    private var subStartPositionMs: Long = 0L
    private var announceToken: Long = 0L
    private var masking: Boolean = false
    private var maskingTrack: AudioTrack? = null
    /** Bumped on every sub arming/teardown; in-flight startOverlap/startOverlay re-check it. */
    private var subGen: Long = 0L
    private var overlapPreparation: TransitionFence? = null

    private var lastMainTrack: AudioTrack? = null
    private var lastPosition: Long = 0L

    /**
     * Lavaplayer nulls `audioPlayer.playingTrack` *before* dispatching the
     * FINISHED/LOAD_FAILED end event, so the handoff guard cannot see the
     * ended track through the player anymore. Recorded under [lock] in
     * [mainListener]'s onTrackEnd; [doHandoff] accepts it as the armed track
     * for [LAST_ENDED_MAIN_GRACE_MS] — a handoff driven by that very end event
     * is a natural finish, not a replace.
     */
    private var lastEndedMain: AudioTrack? = null
    private var lastEndedMainAt: Long = 0L

    /** Ticks once per mixer-filter chunk; see [checkFilterHeartbeatLocked]. */
    private val filterRunCount = AtomicLong(0L)

    // ---- voice-thread feed arbitration ----

    private val electedFeeder = AtomicLong(0L)
    private val electedSeenAtMs = AtomicLong(0L)
    private val rivalWarnAtMs = AtomicLong(0L)
    /** Test seam; production budget is ~50 voice-thread chunks of silence. */
    @Volatile var feederStaleMs: Long = 1000L

    private val chainPos = AtomicLong(0L)
    private var filterRunBaseline = 0L
    private var filterBaselinePos = 0L
    @Volatile private var filterMissing: Boolean = false
    private var filterMissingWarned: Boolean = false
    private val underrunLogAt = AtomicLong(0L)
    private var xfadeLogMs: Long = 0L

    private val mainGain = GainRamp(1f)
    private val subGain = GainRamp(0f)
    /** Written by the voice thread every chunk; control plane only (re)starts the fade. */
    @Volatile private var xfadeLeft: Int = 0
    @Volatile private var xfadeTotal: Int = 1
    /**
     * Decode-side window the fade runs over. Anchored to the *plan*, not to
     * the moment poll() happened to notice the chain crossing [plan.startMs]:
     * the mixer-loop only samples every few milliseconds while the decoder can
     * burst, so a fade that starts "now" would begin late and still be ramping
     * when the outgoing track ends - a crossfade that never gets loud enough
     * to hear. -1 while no planned overlap is running (test seams fall back to
     * the counted-down form).
     */
    @Volatile private var xfadeStartSample: Long = -1L
    @Volatile private var xfadeEndSample: Long = -1L
    @Volatile private var xfadeFadeEndSample: Long = -1L
    @Volatile private var xfadeTransitionKind: String = "crossfade"
    @Volatile private var xfadeDecisionReason: String? = null

    val siphon = SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())
    private val drainFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    private data class StartSnap(val xfade: Boolean, val leadMs: Long, val fadeInMs: Long, val fadeTarget: Float)

    val mainListener = object : AudioEventAdapter() {
        override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
            // Snapshot under lock; the (potentially blocking) seek runs outside it
            // so the voice thread never stalls behind us.
            val snap = synchronized(lock) {
                val currentMain = main?.audioPlayer
                if (currentMain != null && player !== currentMain) return@synchronized null
                tailCutter.reset()
                tailWatch = silenceSkipEnabled && !protocol.preserveTransition
                // The client-side play() is the ground truth for "audio is
                // flowing"; recover the state machine if it lagged behind.
                if (protocol.playbackState == PlaybackState.IDLE || protocol.playbackState == PlaybackState.NO_NEXT) {
                    protocol.playbackState = PlaybackState.PLAYING
                }
                if (track !== protocol.lastCountedTrack) {
                    protocol.lastCountedTrack = track
                    protocol.advanceSeq.incrementAndGet()
                }
                // The mask is identity-checked: another player's TrackStart (two
                // socket sessions) or a client-initiated play must not consume a
                // handoff mask armed for a specific clone.
                if (masking && maskingTrack === track) {
                    masking = false
                    maskingTrack = null
                    null
                } else {
                    if (masking) {
                        masking = false
                        maskingTrack = null
                    }
                    val preserveStart = preservedStartTrack === track
                    preservedStartTrack = null
                    val targetGain = if (overlayActive || parallelOverlays.isNotEmpty()) duckLevel.coerceIn(0f, 1f) else 1f
                    StartSnap(
                        xfadeActive,
                        if (silenceSkipEnabled && !preserveStart) leadForLocked(track) else -1L,
                        if (preserveStart) 0L else fadeInMs,
                        targetGain,
                    )
                }
            }
            if (snap == null) {
                // Masked handoff start: doHandoff already armed mainNormGain
                // for this track; do not reset it.
                return
            }
            // Track-level normalization gain: the first track anchors the
            // session by playing at unity until its background loudness scan
            // finishes; every subsequent track is pulled to that baseline.
            mainNormGain = if (normalizationEnabled) {
                synchronized(lock) { normGainForLocked(track) }
            } else 1f
            // First track played directly: schedule a background loudness scan
            // outside the lock (sub plays block on source I/O) so the next
            // track starts at the anchored gain.
            val needsBaselineAnalysis = if (normalizationEnabled) {
                synchronized(lock) {
                    val id = idOf(track)
                    normGainBaseline == 0f &&
                        !analysisState.active &&
                        readyNextTrack == null &&
                        pendingNextTrack == null &&
                        (id == null || !AnalysisCaches.norm.contains(id))
                }
            } else false
            if (needsBaselineAnalysis) {
                scheduleBaselineAnalysis(track)
            }
            if (bpmDetectionEnabled || keyDetectionEnabled || silenceSkipEnabled) {
                runCatching { track.makeClone() }.getOrNull()?.let { requestPreanalysis(it) }
            }
            if (snap.xfade) {
                // The overlap's own handoff start is masked above, so an
                // unmasked start while armed means the client replaced or
                // stopped the main track. onTrackEnd ignores REPLACED/STOPPED,
                // so without this teardown the armed fade survives in the old
                // track's coordinates and a stale handoff can replace THIS
                // track mid-play. The replacement is a fresh track: lead skip
                // and fade-in below apply to it normally.
                abortXfade("main track replaced mid-crossfade; now '${titleOf(track)}'")
            }
            // Leading-silence skip: start at content, not file start.
            // Queued tracks already seek in seekPastLead; this covers
            // the first track (played via Lavalink REST) when its trim
            // was cached by an earlier preload/analysis.
            if (snap.leadMs > 200 && track.isSeekable) {
                try {
                    val duration = track.duration
                    track.position = if (duration > 0) {
                        maxOf(safePosition(track), snap.leadMs).coerceIn(0, (duration - 500).coerceAtLeast(0))
                    } else maxOf(safePosition(track), snap.leadMs)
                } catch (e: Exception) {
                    log.warn("lead skip seek failed on guild {}", guildId, e)
                }
            }
            if (!snap.xfade && snap.fadeInMs > 0) {
                mainGain.setNow(0f)
                mainGain.rampTo(snap.fadeTarget, msToSamples(snap.fadeInMs))
            }
            // After the lead-skip seek above: this is where the chain resumes.
            syncChainPos(track.position)
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            if (endReason != AudioTrackEndReason.FINISHED && endReason != AudioTrackEndReason.LOAD_FAILED) {
                if (endReason == AudioTrackEndReason.STOPPED || endReason == AudioTrackEndReason.CLEANUP) {
                    synchronized(lock) {
                        if (!xfadeActive && main?.audioPlayer?.playingTrack == null) {
                            protocol.playbackState = PlaybackState.IDLE
                        }
                    }
                }
                return
            }
            synchronized(lock) {
                // The player has already null'ed playingTrack by now (lavaplayer
                // clears it before dispatching this event); remember the ended
                // instance so doHandoff can tell a natural finish from a replace.
                lastEndedMain = track
                lastEndedMainAt = System.currentTimeMillis()
            }
            scheduleMainRecovery(player, track)
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' stuck on guild {} (threshold {}ms); mixer failover", title, guildId, thresholdMs)
            scheduleMainRecovery(player, track)
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' failed on guild {}; mixer failover", title, guildId, exception)
            scheduleMainRecovery(player, track)
        }
    }

    private fun scheduleMainRecovery(player: AudioPlayer, track: AudioTrack) {
        val fence = synchronized(lock) {
            if (main?.audioPlayer !== player) return
            val playing = player.playingTrack
            if (playing != null && playing !== track) return
            transitionFenceLocked()
        }
        protocol.executeAction {
            synchronized(playerMutationLock) {
                val current = synchronized(lock) { main?.audioPlayer === player && matchesFenceLocked(fence) }
                if (current) recoverFromMainStall()
            }
        }
    }

    /**
     * A stalled main track never reaches FINISHED, so without this the mixer
     * would hang: during a crossfade the handoff position never arrives (the
     * position is frozen), and otherwise the queued track never starts.
     * Failing over keeps audio flowing; the TrackStuck/Exception event itself
     * is still delivered to the client by the server's own listener.
     */
    private fun recoverFromMainStall() {
        val handoff = synchronized(lock) { xfadeActive }
        if (handoff) {
            doHandoff()
            return
        }
        fallbackAdvanceOrIdle()
    }

    private val subListener = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            val overlay = synchronized(lock) { overlayActive }
            if (overlay) {
                finishOverlay()
                return
            }
            val abort = synchronized(lock) {
                if (!xfadeActive || subTrack !== track) false
                else !(endReason == AudioTrackEndReason.FINISHED && xfadeStartSample >= 0 &&
                    siphon.capturedOutputEnd() >= xfadeEndSample - xfadeStartSample)
            }
            if (abort) abortXfade("sub track ended: $endReason")
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            log.warn("secondary track '{}' failed on guild {}: {}", titleOf(track), guildId, exception.toString())
            subFailed()
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            log.warn("secondary track '{}' stuck on guild {} ({}ms); failover", titleOf(track), guildId, thresholdMs)
            subFailed()
        }

        private fun subFailed() {
            val action = synchronized(lock) {
                when {
                    overlayActive -> 1
                    xfadeActive -> 2
                    else -> 0
                }
            }
            when (action) {
                1 -> finishOverlay()
                2 -> abortXfade("sub track stuck/failed")
            }
        }
    }

    /** Listener for a single `mode=parallel` overlay slot; self-finishes on end/failure. */
    private fun parallelOverlayListener(id: Long): AudioEventAdapter = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            finishParallelOverlay(id)
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            log.warn("parallel overlay '{}' failed on guild {}: {}", titleOf(track), guildId, exception.toString())
            finishParallelOverlay(id)
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            log.warn("parallel overlay '{}' stuck on guild {} ({}ms)", titleOf(track), guildId, thresholdMs)
            finishParallelOverlay(id)
        }
    }

    fun attachMain(player: IPlayer) {
        synchronized(playerMutationLock) {
            val newAudioPlayer = player.audioPlayer
            val (previousPlayers, addListener) = synchronized(lock) {
                val previous = attachedPlayers.filter { it !== newAudioPlayer }
                attachedPlayers.removeAll(previous.toSet())
                main = player
                val added = attachedPlayers.add(newAudioPlayer)
                if (previous.isNotEmpty()) electedFeeder.set(0L)
                previous to added
            }
            // Listener registration also takes Lavaplayer's trackSwitchLock.
            for (old in previousPlayers) old.removeListener(mainListener)
            if (addListener) newAudioPlayer.addListener(mainListener)
            for (old in previousPlayers) {
                try {
                    old.stopTrack()
                } catch (e: Exception) {
                    log.debug("stopping replaced session player failed on guild {}: {}", guildId, e.toString())
                }
            }
        }
    }

    /** Whether this mixer's main (newest attached) player is [player]. */
    fun isMain(player: IPlayer): Boolean = main === player

    fun newFilter(downstream: com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter? = null): MixerFilter =
        MixerFilter(this, downstream)

    // ---- voice-thread entry points ----

    /** Advances ramps by [samples] and returns the gains to mix with. */
    fun advance(samples: Int, feederId: Long): Gains {
        if (!claimFeeder(feederId)) {
            // Rival chain: snapshot only — mutating ramps/chainPos here would
            // double every fade and run the chain counter at 2x.
            return Gains(mainGain.current * mainNormGain, subGain.current * subNormGain)
        }
        val endOfChunk = chainPos.addAndGet(samples.toLong())
        // Lock-free on purpose: this runs on the voice thread for every ~20ms
        // chunk, and blocking here stalls main-track output until Lavaplayer
        // reports the (healthy) track as stuck. GainRamp fields are volatile;
        // a race with the control plane only shifts a fade by one chunk.
        if (xfadeActive) {
            val recipe = activeRecipe
            if (recipe != null) {
                val start = xfadeStartSample
                val currentSample = (endOfChunk - start).coerceAtLeast(0L)
                val m = recipe.gainAt(TrackTarget.OUTGOING, currentSample, 1.0f)
                val s = recipe.gainAt(TrackTarget.INCOMING, currentSample, 0.0f)
                mainGain.setNow(m)
                subGain.setNow(s)
            } else {
                val start = xfadeStartSample
                val end = xfadeFadeEndSample.takeIf { it >= 0 } ?: xfadeEndSample
                val t = if (start >= 0 && end > start) {
                    val elapsed = (endOfChunk - start).coerceIn(0L, end - start)
                    xfadeLeft = (end - endOfChunk).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    elapsed.toFloat() / (end - start)
                } else {
                    val total = xfadeTotal.coerceAtLeast(1)
                    val left = (xfadeLeft - samples).coerceAtLeast(0)
                    xfadeLeft = left
                    1f - left.toFloat() / total
                }
                val u = t.coerceIn(0f, 1f)
                // Equal-power curve keeps perceived loudness flat through the overlap.
                mainGain.setNow(cos(u * PI.toFloat() / 2f))
                subGain.setNow(sin(u * PI.toFloat() / 2f))
            }
        } else {
            mainGain.advance(samples)
            subGain.advance(samples)
        }
        return Gains(mainGain.current * mainNormGain, subGain.current * subNormGain)
    }

    fun takeSub(samples: Int, channels: Int, out: Array<FloatArray>): Int {
        if (!xfadeActive) return siphon.take(samples, channels, out)
        val start = xfadeStartSample
        if (start < 0) {
            val read = siphon.takeWithSource(samples, channels, out)
            subSourceMilliSamples.addAndGet(read.sourceMilliSamples)
            return read.outputSamples
        }
        val chunkStart = chainPos.get() - samples
        val desired = (chunkStart - start).coerceAtLeast(0L)
        val prefix = (start - chunkStart).coerceIn(0L, samples.toLong()).toInt()
        val read = siphon.takeAt(desired, samples - prefix, channels, out)
        if (prefix > 0) {
            for (c in 0 until minOf(channels, out.size)) {
                out[c].copyInto(out[c], prefix, 0, samples - prefix)
                out[c].fill(0f, 0, prefix)
            }
        }
        val end = xfadeEndSample
        if (end < 0 || chunkStart < end) {
            subSourceMilliSamples.set(read.sourcePositionMilliSamples)
        }
        return read.outputSamples
    }

    /** Whether [feederId] currently owns the feed; settle [claimFeeder] (via [advance]) before reading. */
    fun isFeeder(feederId: Long): Boolean = electedFeeder.get() == feederId

    /** Called by [MixerFilter.close] when Lavaplayer tears its chain down (hot-swap, track end). */
    fun retireFeeder(feederId: Long) {
        electedFeeder.compareAndSet(feederId, 0L)
    }

    private fun claimFeeder(feederId: Long): Boolean {
        while (true) {
            val elected = electedFeeder.get()
            if (elected == feederId) {
                electedSeenAtMs.set(System.currentTimeMillis())
                return true
            }
            if (elected != 0L) {
                val silentMs = System.currentTimeMillis() - electedSeenAtMs.get()
                if (silentMs < feederStaleMs) {
                    warnRivalFeeder(feederId, elected)
                    return false
                }
                log.info(
                    "mixer feeder takeover on guild {}: incumbent filter #{} silent for {}ms, filter #{} takes over",
                    guildId, elected, silentMs, feederId,
                )
            }
            if (electedFeeder.compareAndSet(elected, feederId)) {
                electedSeenAtMs.set(System.currentTimeMillis())
                return true
            }
        }
    }

    private fun warnRivalFeeder(feederId: Long, elected: Long) {
        val now = System.currentTimeMillis()
        val last = rivalWarnAtMs.get()
        if (now - last < 60_000 || !rivalWarnAtMs.compareAndSet(last, now)) return
        log.warn(
            "second mixer filter (#{} on guild {}) is feeding a mixer owned by filter #{} — two players exist " +
                "for this guild (stale session vs fresh restore?). Only the incumbent advances the chain; " +
                "check for duplicate session players.",
            feederId, guildId, elected,
        )
    }

    /**
     * Advances the per-slot fade ramps and returns the currently active
     * parallel overlays (player + siphon + ramp) for the mixer to sum in.
     * Called on the voice thread each chunk; slots are read lock-free via
     * volatile/immutable handles, and only the ramp advances here.
     */
    fun advanceParallelOverlays(samples: Int, feederId: Long): List<OverlaySlot> {
        if (!isFeeder(feederId)) return emptyList()
        val active = parallelOverlaySnapshot.filter { it.active }
        for (slot in active) slot.ramp.advance(samples)
        return active
    }

    /** Counts only PCM that is actually mixed into the Discord output. */
    fun noteSubOutput(samples: Int) {
        if (samples <= 0 || !xfadeActive) return
        subOutputSamples.addAndGet(samples.toLong())
    }

    /** Heartbeat from the voice path: the mixer filter processed a chunk. */
    fun noteFilterRun() {
        filterRunCount.incrementAndGet()
    }

    /** Re-bases the decode-side position after a track start or a seek. */
    fun syncChainPos(positionMs: Long) {
        if (positionMs >= 0) chainPos.set(positionMs * SAMPLE_RATE / 1000)
    }

    internal fun chainPosSamples(): Long = chainPos.get()

    /**
     * Throttled DEBUG diagnostic: an overlap is fading in but the
     * secondary decoder did not supply the whole chunk, so the mix dropped
     * to silence on the sub side for this frame.
     */
    fun noteUnderrun(missing: Int, requested: Int) {
        if (!log.isDebugEnabled) return
        val prefix = if (xfadeStartSample >= 0L) {
            (xfadeStartSample - (chainPos.get() - requested)).coerceIn(0L, requested.toLong()).toInt()
        } else 0
        val starved = (missing - prefix).coerceAtLeast(0)
        if (starved == 0) return
        if (!xfadeActive || missing <= 0) return
        val now = System.currentTimeMillis()
        val last = underrunLogAt.get()
        if (now - last < 1000 || !underrunLogAt.compareAndSet(last, now)) return
        log.debug(
            "crossfade sub starvation on guild {}: {} of {} samples silent " +
                "(pending={}f/{}ms dropped={}f/{}ms subOut={}ms)",
            guildId, starved, requested - prefix,
            siphon.pendingFrames, siphon.pendingSamples * 1000L / SAMPLE_RATE,
            siphon.droppedFrames, siphon.droppedSamples,
            subOutputSamples.get() * 1000L / SAMPLE_RATE,
        )
    }

    /**
     * Threshold amp for realtime tail watching, or null when no peak
     * computation is needed this chunk (voice-thread fast path).
     */
    fun realtimeTailAmp(): Float? =
        if (tailWatch && silenceSkipEnabled && !protocol.preserveTransition) SilenceAnalyzer.thresholdAmp(silenceThresholdDb) else null

    fun noteChunk(peak: Float, samples: Int, thresholdAmp: Float) =
        tailCutter.note(peak, samples, thresholdAmp, chainPos.get())

    /** Test seam: bypass ramps and pin gains. */
    internal fun gainsForTest(main: Float, sub: Float) {
        mainGain.setNow(main)
        subGain.setNow(sub)
    }

    /** Test seam: pin the normalization gains without a decoder. */
    internal fun normalizeForTest(mainGain: Float, subGain: Float = 1f) {
        mainNormGain = mainGain
        subNormGain = subGain
    }

    /** Test seam: read the sub normalization gain without a decoder. */
    internal fun subNormGainForTest(): Float = subNormGain

    /**
     * Test seam: seed the first-track baseline and optionally the track's
     * measured RMS so [normGainForLocked] can compute a real gain.
     */
    internal fun normBaselineForTest(rms: Float, trackId: String? = null, trackRms: Float? = null) {
        normGainBaseline = rms
        if (trackId != null && trackRms != null) {
            AnalysisCaches.norm[trackId] = trackRms
        }
    }

    /** Test seam: arm a fake overlap so [advance]/[noteSubOutput] can be driven without players. */
    internal fun beginXfadeForTest(totalSamples: Int) {
        xfadeTotal = totalSamples.coerceAtLeast(1)
        xfadeLeft = xfadeTotal
        xfadeStartSample = -1L
        xfadeEndSample = -1L
        xfadeFadeEndSample = -1L
        subOutputSamples.set(0L)
        subSourceMilliSamples.set(0L)
        xfadeActive = true
    }

    internal fun endXfadeForTest() {
        xfadeActive = false
        activeRecipe = null
    }

    /** Test seam: fully reset overlap state without touching sub listeners. */
    internal fun clearXfadeForTest() {
        synchronized(lock) {
            xfadeActive = false
            activeRecipe = null
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
            xfadeFadeEndSample = -1L
            xfadeMainTrack = null
        }
    }

    /** Test seam: arm the handoff state as if [mainTrack]'s overlap were running, without driving players. */
    internal fun armHandoffForTest(mainTrack: AudioTrack?, handoffMs: Long) {
        synchronized(lock) {
            xfadeActive = true
            xfadeHandoffMs = handoffMs
            xfadeMainTrack = mainTrack
        }
    }

    internal fun subOutputSamplesForTest(): Long = subOutputSamples.get()
    internal fun subSourceSamples(): Long = (subSourceMilliSamples.get() + 500L) / 1000L
    internal fun isXfadeBeatMatchedForTest(): Boolean = xfadeBeatMatched
    internal fun currentSubEffectiveSpeedForTest(): Double = siphonFactory?.effectiveSpeed ?: 1.0

    internal fun filterRunsForTest(): Long = filterRunCount.get()

    internal fun filterMissingForTest(): Boolean = filterMissing

    /** Advances the heartbeat baseline as if a fresh main track just started. */
    internal fun resetFilterHeartbeatForTest(position: Long = 0L) {
        synchronized(lock) { resetFilterHeartbeatLocked(position) }
    }

    internal fun trimForTest(identifier: String): TrimPoints? =
        synchronized(lock) { AnalysisCaches.trim[identifier] }

    /** Test seam: count currently active parallel overlay slots. */
    internal fun parallelOverlayCountForTest(): Int = synchronized(lock) { parallelOverlays.size }
    internal fun installParallelOverlayForTest(slot: OverlaySlot) {
        synchronized(lock) {
            parallelOverlays[slot.id] = slot
            publishParallelOverlaysLocked()
        }
    }
    internal fun overlayForTest(active: Boolean, channel: AnnounceChannel = AnnounceChannel.BOTH) {
        synchronized(lock) {
            overlayActive = active
            sequentialOverlayChannel = channel
        }
    }

    /** Test seam: simulate [count] active parallel slots without players (for the limit check). */
    internal fun armParallelForTest(count: Int) {
        synchronized(lock) {
            parallelOverlays.clear()
            repeat(count) { i ->
                parallelOverlays[i.toLong()] = OverlaySlot(i.toLong(), playerManager.createPlayer(), SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())).apply { active = true }
            }
            publishParallelOverlaysLocked()
        }
    }

    // ---- scheduler entry points ----

    /** Keeps the secondary decoder flowing; its PCM is captured by the siphon filter. */
    fun drain() {
        val sub = synchronized(lock) { subPlayer?.takeIf { it.playingTrack != null } }
        // Providing frees the decoder's frame buffer and lets it produce more PCM.
        // Keep prewarmed audio bounded while the main decoder has not reached the fade.
        if (sub != null && (overlayActive || siphon.pendingSamples < SAMPLE_RATE)) {
            try {
                sub.provide(drainFrame)
            } catch (e: Exception) {
                log.warn("sub drain failed on guild {}", guildId, e)
            }
        }
        // Drive every active parallel overlay decoder too; each has its own
        // player and siphon, and none of them are drained anywhere else.
        val parallels = synchronized(lock) {
            parallelOverlays.values.filter { it.active && it.player.playingTrack != null }
        }
        for (slot in parallels) {
            try {
                slot.player.provide(drainFrame)
            } catch (e: Exception) {
                log.warn("parallel overlay drain failed on guild {}", guildId, e)
            }
        }
    }

    private data class TransitionFence(val outgoing: AudioTrack?, val incoming: AudioTrack?, val epoch: Long, val seq: Long, val slotRevision: Long)

    private fun transitionFenceLocked() = TransitionFence(main?.audioPlayer?.playingTrack,
        readyNextTrack ?: pendingNextTrack, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), slotRevision)

    private fun matchesFenceLocked(fence: TransitionFence): Boolean =
        main?.audioPlayer?.playingTrack === fence.outgoing && (readyNextTrack ?: pendingNextTrack) === fence.incoming &&
            protocol.gate.lastKnownEpoch == fence.epoch && protocol.advanceSeq.get() == fence.seq && slotRevision == fence.slotRevision

    private sealed interface PollAction {
        data object None : PollAction
        data class StartOverlap(val plan: CrossfadePlan, val fence: TransitionFence, val analysisLog: String) : PollAction
        data object Handoff : PollAction
        data class AdvanceNext(val fence: TransitionFence) : PollAction
        data class AbortXfade(val reason: String) : PollAction
    }

    /**
     * Starts/finalizes a transition against the final *audible* sample.
     * A detected trailing silent segment is never played or included in a
     * crossfade: the overlap ends at `duration - trailingSilence`.
     *
     * Only cheap state reads happen under [lock]; player/track calls
     * (play, seek, stop) run outside it so a slow source can never stall
     * the voice thread into a false TrackStuck.
     */
    fun poll() {
        val action = synchronized(lock) { decidePollLocked() }
        executePoll(action)
    }

    private fun executePoll(action: PollAction) {
        // Read the transition state before acting on it: doHandoff clears it.
        if (xfadeActive && log.isDebugEnabled) logXfadeProgress(action)
        when (action) {
            is PollAction.None -> Unit
            is PollAction.StartOverlap -> prepareOverlap(action.plan, action.fence, action.analysisLog)
            is PollAction.Handoff -> doHandoff()
            is PollAction.AdvanceNext -> advanceNext(action.fence)
            is PollAction.AbortXfade -> abortXfade(action.reason)
        }
        checkJitWindow()
    }

    /** Detailed overlap progress, logged at most once per second at DEBUG. */
    private fun logXfadeProgress(action: PollAction) {
        val now = System.currentTimeMillis()
        if (now - xfadeLogMs < 1000) return
        xfadeLogMs = now
        val track = main?.audioPlayer?.playingTrack ?: return
        val pos = safePosition(track)
        val chain = chainPos.get() * 1000L / SAMPLE_RATE
        val handoff: Long
        val subPos: Long
        val subOutMs: Long
        synchronized(lock) {
            handoff = xfadeHandoffMs
            subPos = subTrack?.let { safePosition(it) } ?: -1L
            subOutMs = subOutputSamples.get()
        }
        log.debug(
            "crossfade progress guild {} at {}ms (decoded {}ms) /handoff {}ms ({}ms left, action={}): " +
                "pending={}f/{}ms dropped={}f subOut={}ms subPos={}ms mixerFilterCalls={}",
            guildId, pos, chain, handoff, (handoff - pos).coerceAtLeast(0), action,
            siphon.pendingFrames, siphon.pendingSamples * 1000L / SAMPLE_RATE,
            siphon.droppedFrames,
            subOutMs * 1000L / SAMPLE_RATE,
            subPos,
            filterRunCount.get(),
        )
    }

    private fun decidePollLocked(): PollAction {
        refreshBeatmatchStatusLocked()
        val audioPlayer = main?.audioPlayer ?: return PollAction.None
        val track = audioPlayer.playingTrack ?: run {
            lastMainTrack = null
            tailWatch = false
            return PollAction.None
        }
        if (track !== lastMainTrack) {
            lastMainTrack = track
            lastPosition = safePosition(track)
            resetFilterHeartbeatLocked(lastPosition)
            // TrackStart should already have torn down an armed overlap; this
            // covers event ordering/loss so a zombie fade never hands off on
            // a track it was not armed on.
            if (xfadeActive) return PollAction.AbortXfade("main track changed")
            return PollAction.None
        }
        val pos = safePosition(track)
        if (pos < lastPosition - 1500) {
            // User seeked backwards: any armed overlap is stale.
            tailCutter.reset()
            resetFilterHeartbeatLocked(pos)
            syncChainPos(pos)
            if (xfadeActive) return PollAction.AbortXfade("seek during crossfade")
        } else if (pos > lastPosition + 2500 && pos * SAMPLE_RATE / 1000 > chainPos.get() + SAMPLE_RATE) {
            // Forward seek (e.g. fresh session restore seeking to saved position):
            // re-base chainPos if track.position jumped ahead of the decoded chain.
            tailCutter.reset()
            resetFilterHeartbeatLocked(pos)
            syncChainPos(pos)
        }
        lastPosition = pos
        checkFilterHeartbeatLocked(audioPlayer, pos)
        // Opportunistic promotion: analysis may have finished since queue.
        promoteIfPendingLocked()
        tailWatch = silenceSkipEnabled && !protocol.preserveTransition
        if (!xfadeActive && (readyNextTrack != null || pendingNextTrack != null)) {
            val duration = track.duration
            if (duration > 0 && duration < Long.MAX_VALUE / 2) {
                val trail = if (silenceSkipEnabled && !protocol.preserveTransition) trailForLocked(track) else 0L
                val requestedEnd = protocol.armedOutgoingEndPositionMs.takeIf {
                    it > 0L && protocol.armedOutgoingTrack === track
                }
                val contentEnd = minOf(TrimTransition.contentEnd(duration, trail), requestedEnd ?: duration)
                val plan = planCrossfadeLocked(track, readyNextTrack ?: pendingNextTrack, contentEnd)
                val silenceBeforeOverlap = !protocol.preserveTransition && silenceSkipEnabled && plan != null &&
                    tailCutter.isConfirmedBefore(plan.startMs, duration, tailConfirmMs, silenceTailScanMs)
                if (!protocol.preserveTransition && silenceSkipEnabled &&
                    tailCutter.shouldCut(pos, duration, tailConfirmMs, silenceTailScanMs)
                ) {
                    // Realtime fallback: sustained silence inside the tail window.
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext(transitionFenceLocked())
                } else if (overlapPreparation == null && readyNextTrack != null && plan != null && !silenceBeforeOverlap &&
                    chainPos.get() >= (plan.startMs - 2000L).coerceAtLeast(0L) * SAMPLE_RATE / 1000
                ) {
                    // Chain, not track.position: the mix lands on frames as they
                    // are decoded, which runs a frame buffer ahead of playback.
                    val incoming = readyNextTrack!!
                    if (!incomingFeaturesReadyLocked(incoming)) {
                        // A late arm left the incoming track's beatmatching scan
                        // still running when the fade window opened. Wait for the
                        // scan to land instead of firing a plain fade with
                        // missing_features; at file end advance anyway (degraded).
                        if (pos < duration - 500) return PollAction.None
                        return PollAction.AdvanceNext(transitionFenceLocked())
                    }
                    val fence = transitionFenceLocked()
                    val analysisLog = transitionAnalysisLog(fence.outgoing?.let { idOf(it) }, fence.incoming?.let { idOf(it) }) +
                        " limits={minConfidence=$beatmatchMinConfidence maxTempoChange=$maxTempoChange}"
                    return PollAction.StartOverlap(plan, fence, analysisLog)
                } else if (adaptiveTrim != null && pos >= adaptiveTrim!!.decision.outgoingContentEndMs) {
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext(transitionFenceLocked())
                } else if (requestedEnd != null && pos >= contentEnd) {
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext(transitionFenceLocked())
                } else if (!protocol.preserveTransition && silenceSkipEnabled && trail > 0 && pos >= contentEnd) {
                    // End exactly at the last audible sample; do not emit the file's tail silence.
                    // Force-promote pending so a slow analysis never plays silence.
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext(transitionFenceLocked())
                } else if (readyNextTrack == null && pendingNextTrack != null && pos >= duration - 500) {
                    // Analysis still running at file end: advance anyway (degraded, no trim).
                    promoteForcedLocked()
                    return PollAction.AdvanceNext(transitionFenceLocked())
                }
            } else if (!protocol.preserveTransition && silenceSkipEnabled &&
                tailCutter.shouldCut(pos, -1, tailConfirmMs, silenceTailScanMs)
            ) {
                // Streams have no pre-analysis tail; realtime is the only signal.
                if (readyNextTrack == null) promoteForcedLocked()
                return PollAction.AdvanceNext(transitionFenceLocked())
            }
            return PollAction.None
        } else if (xfadeActive && xfadeMainTrack != null && track !== xfadeMainTrack) {
            // The armed window is in the replaced track's coordinates; firing
            // it now would swap this track for the fading-in sub track.
            return PollAction.AbortXfade("armed main track no longer playing")
        } else if (xfadeActive && xfadeHandoffMs >= 0 && pos >= xfadeHandoffMs) {
            // `FINISHED` occurs at the physical file end. Switch at the
            // audible end instead, so detected trailing silence is gone.
            return PollAction.Handoff
        } else {
            return PollAction.None
        }
    }

    private fun refreshBeatmatchStatusLocked() {
        if (xfadeBeatMatched && siphonFactory?.nativeFailed == true) {
            xfadeBeatMatched = false
            xfadeFallbackReason = "timescale_native_failed"
            transitionStatus = TransitionStatus("crossfade", "timescale_native_failed")
        }
    }

    private fun planCrossfadeLocked(
        outgoingTrack: AudioTrack,
        incomingTrack: AudioTrack?,
        contentEndMs: Long,
    ): CrossfadePlan? {
        adaptiveTrim = null
        if (protocol.preserveTransition) {
            transitionStatus = TransitionStatus("preserve", "preserve_context")
            return null
        }
        if (!outgoingTrack.isSeekable || !crossfadeEnabled) {
            transitionStatus = TransitionStatus("preserve", "crossfade_unavailable")
            return null
        }
        if (!beatmatchEnabled || !bpmDetectionEnabled || incomingTrack == null || !incomingTrack.isSeekable) {
            val reason = when {
                !beatmatchEnabled -> "beatmatch_disabled"
                !bpmDetectionEnabled -> "bpm_disabled"
                incomingTrack == null -> "missing_incoming_track"
                else -> "incoming_unseekable"
            }
            transitionStatus = TransitionStatus("crossfade", reason)
            return TrimTransition.crossfade(contentEndMs, crossfadeMs)?.copy(beatMatched = false, fallbackReason = reason)
        }
        val outId = idOf(outgoingTrack)
        val inId = idOf(incomingTrack)
        val outGrid = outId?.let { AnalysisCaches.bpmTail[it] ?: AnalysisCaches.bpmHead[it] }
        val inGrid = inId?.let { AnalysisCaches.bpmHead[it] }
        val inFeatures = inId?.let { AnalysisCaches.featuresHead[it] }
        if (protocol.armedStartPositionMs > 0L &&
            (inFeatures == null || protocol.armedStartPositionMs >= inFeatures.windowEndMs - 500L)
        ) {
            transitionStatus = TransitionStatus("crossfade", "incoming_start_outside_analysis")
            return TrimTransition.crossfade(contentEndMs, crossfadeMs)?.copy(
                beatMatched = false,
                fallbackReason = "incoming_start_outside_analysis",
                incomingStartMs = protocol.armedStartPositionMs,
            )
        }
        val inLead = maxOf(if (silenceSkipEnabled) leadForLocked(incomingTrack) else 0L, protocol.armedStartPositionMs)
        val inDuration = try { incomingTrack.duration } catch (e: Exception) { -1L }
        val decision = AdaptiveTransitionPlanner.decide(
            outgoingFeatures = outId?.let { AnalysisCaches.featuresTail[it] },
            incomingFeatures = inFeatures,
            outgoingGrid = outGrid,
            incomingGrid = inGrid,
            contentEndMs = contentEndMs,
            requestedCrossfadeMs = crossfadeMs,
            maxTempoChange = maxTempoChange,
            minConfidence = beatmatchMinConfidence,
            incomingLeadMs = inLead,
            incomingDurationMs = inDuration,
        )
        transitionStatus = when (decision) {
            is AdaptiveTransitionDecision.Preserve -> TransitionStatus("preserve", decision.reason)
            is AdaptiveTransitionDecision.SilenceTrim -> TransitionStatus("silence_trim", decision.reason)
            is AdaptiveTransitionDecision.ShortCrossfade -> TransitionStatus("short_crossfade", decision.reason)
            is AdaptiveTransitionDecision.BeatMatched -> TransitionStatus("beat_matched", "compatible_candidates")
        }
        if (decision is AdaptiveTransitionDecision.SilenceTrim) adaptiveTrim = PlannedTrim(outgoingTrack, incomingTrack, decision)
        val v2Recipe = AdaptiveTransitionPlannerV2.plan(
            outgoingGridTail = outGrid?.toExtended(),
            incomingGridHead = inGrid?.toExtended(),
            outgoingFeaturesTail = outId?.let { AnalysisCaches.featuresTail[it] },
            incomingFeaturesHead = inFeatures,
            requestedCrossfadeMs = crossfadeMs,
            preserveContext = protocol.preserveTransition,
        )
        plannedRecipe = v2Recipe
        return AdaptiveTransitionPlanner.toCrossfadePlan(decision, contentEndMs)?.copy(
            transitionKind = transitionStatus.kind, reason = transitionStatus.reason,
        )
    }

    private fun resetFilterHeartbeatLocked(position: Long) {
        filterRunBaseline = filterRunCount.get()
        filterBaselinePos = position
        filterMissing = false
        filterMissingWarned = false
    }

    private fun checkFilterHeartbeatLocked(audioPlayer: AudioPlayer, pos: Long) {
        val runs = filterRunCount.get()
        if (runs != filterRunBaseline) {
            if (filterMissing) {
                filterMissing = false
                log.info("mixer filter is live on guild {} ({} chunks since track start)", guildId, runs - filterRunBaseline)
            }
            return
        }
        if (filterMissingWarned || main == null || audioPlayer.isPaused) return
        val played = pos - filterBaselinePos
        if (played < 1000) return
        filterMissingWarned = true
        filterMissing = true
        log.warn(
            "mixer filter is NOT in the running track's chain on guild {}: {}ms decoded, " +
                "0 mixer filter calls. Send the `filters` op with pluginFilters.mixer BEFORE " +
                "`play` (or re-send `filters` and seek) — Lavalink does not rebuild the chain " +
                "of a track that is already playing.",
            guildId, played,
        )
    }


    // ---- control-plane entry points (REST) ----

    fun queueNext(future: CompletableFuture<AudioTrack>) {
        future.whenComplete { track, err ->
            if (err == null && track != null) {
                synchronized(lock) {
                    slotRevision++
                    adaptiveTrim = null
                    protocol.preserveTransition = false
                    protocol.armedStartPositionMs = 0L
                    protocol.armedOutgoingEndPositionMs = 0L
                    protocol.armedOutgoingTrack = null
                    // Overwrite any previous slot; promote immediately when
                    // trim is already cached, otherwise wait for analysis.
                    readyNextTrack = null
                    pendingNextTrack = track
                    protocol.playbackState = PlaybackState.ARMED
                    promoteIfPendingLocked()
                }
                log.info("queued next track '{}' ({}) on guild {}", track.info.title, track.identifier, guildId)
            }
        }
    }

    fun clearNext(): Boolean = synchronized(lock) {
        slotRevision++
        adaptiveTrim = null
        val had = pendingNextTrack != null || readyNextTrack != null
        val cleared = readyNextTrack ?: pendingNextTrack
        pendingNextTrack = null
        readyNextTrack = null
        if (cleared != null) {
            log.info("cleared queued next track '{}' on guild {}", titleOf(cleared), guildId)
        }
        had
    }

    fun hasNext(): Boolean = synchronized(lock) { pendingNextTrack != null || readyNextTrack != null }

    fun isOverlayActive(): Boolean = overlayActive
    fun currentOverlayChannel(): AnnounceChannel = sequentialOverlayChannel
    fun isXfadeActive(): Boolean = synchronized(lock) { xfadeActive }
    fun isSubBusy(): Boolean = synchronized(lock) { overlayActive || xfadeActive || overlapPreparation != null || analysisState.active || parallelOverlays.isNotEmpty() }

    // ---- announcement queue ----
    //
    // TTS/announcements are talk-over overlays: they duck the music rather than
    // becoming a track in the playback order. One overlay at a time is a hard
    // limit (there is a single secondary decoder), so successive announcements
    // wait here and play back to back as the secondary player frees up.


    private val announcementQueue = AnnouncementQueue(
        guildId = guildId,
        activeParallelCount = { parallelOverlays.size },
        maxParallelOverlays = { maxParallelOverlays },
        isSequentialBusy = { overlayActive || xfadeActive || overlapPreparation != null },
    )

    /** Queues an announcement; returns its id and 1-based position among queued announcements. */
    fun enqueueAnnouncement(
        identifier: String?,
        encodedTrack: String?,
        duckLevel: Float,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
    ): Pair<Long, Int> =
        synchronized(lock) {
            announcementQueue.enqueue(identifier, encodedTrack, duckLevel, mode, channel)
        }

    fun takeNextAnnouncement(): AnnouncementItem? = synchronized(lock) {
        announcementQueue.takeNext()
    }

    /** Puts an item back at the head after a busy race or transient failure. */
    fun requeueAnnouncement(item: AnnouncementItem, cause: Throwable?) {
        synchronized(lock) {
            announcementQueue.requeue(item)
        }
        log.warn("announcement {} re-queued on guild {}: {}", item.id, guildId, cause?.toString() ?: "secondary player busy")
    }

    /** Drops an item whose track could not be resolved/started. */
    fun onAnnouncementFailed(item: AnnouncementItem, cause: Throwable?) {
        synchronized(lock) { announcementQueue.markFinishedResolution() }
        log.warn("announcement {} on guild {} dropped: {}", item.id, guildId, cause?.toString() ?: "unknown")
    }

    fun onAnnouncementStarted(item: AnnouncementItem) {
        synchronized(lock) { announcementQueue.markFinishedResolution() }
    }

    fun announcementQueueSize(): Int = synchronized(lock) { announcementQueue.size }

    /** Removes all queued (not active) announcements; returns how many were dropped. */
    fun clearAnnouncements(): Int = synchronized(lock) {
        announcementQueue.clear()
    }

    /** Normalization gain of the currently playing main track (1 while unknown/disabled). */
    fun currentTrackGain(): Float = mainNormGain

    /** Analyzed gain of the queued next track; null while analysis is pending. */
    fun nextTrackGain(): Float? = synchronized(lock) {
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        normGainForLocked(next).takeIf { it != 1f }
    }

    /** Identifier of the preheated next track (pending or ready), so clients can verify which track the server will start. */
    fun nextTrackIdentifier(): String? = synchronized(lock) { (readyNextTrack ?: pendingNextTrack)?.let { idOf(it) } }

    fun nextTrackTitle(): String? = synchronized(lock) { (readyNextTrack ?: pendingNextTrack)?.let { titleOf(it) } }

    /** Identifier of the armed overlay/overlap sub track; non-null while the secondary decoder is in use. */
    fun subTrackIdentifier(): String? = synchronized(lock) { subTrack?.let { idOf(it) } }

    fun analysisState(): String = synchronized(lock) {
        when {
            analysisState.active -> "analyzing"
            readyNextTrack != null -> "ready"
            pendingNextTrack != null -> "pending"
            else -> "idle"
        }
    }

    /** False once the heartbeat probe proved the mixer filter never entered the running track's chain. */
    fun mixerFilterActive(): Boolean = synchronized(lock) { !filterMissing }

    /** Milliseconds until an armed overlap hands off; null when no crossfade is running. */
    fun crossfadeHandoffInMs(): Long? = synchronized(lock) {
        if (!xfadeActive || xfadeHandoffMs < 0) return null
        val pos = main?.audioPlayer?.playingTrack?.let { safePosition(it) } ?: 0L
        (xfadeHandoffMs - pos).coerceAtLeast(0)
    }

    fun startOverlay(
        track: AudioTrack,
        duck: Float,
        announcementId: Long? = null,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
    ): AudioTrack {
        if (mode == AnnounceMode.PARALLEL) {
            startParallelOverlay(track, duck, announcementId, channel)
            return track
        }
        val (sub, gen) = synchronized(lock) {
            if (overlayActive || xfadeActive || overlapPreparation != null) throw MixerBusyException("secondary player busy on guild $guildId")
            val s = ensureSubLocked()
            siphonFactory?.resetSpeed()
            announceToken++
            subTrack = track
            overlayActive = true
            sequentialOverlayId = announcementId
            sequentialOverlayChannel = channel
            subStartMs = System.currentTimeMillis()
            siphon.clear()
            // Overlays are announcements: they use their original level, not
            // the outgoing crossfade track's normalization gain.
            subNormGain = 1f
            mainGain.rampTo(duck.coerceIn(0f, 1f), msToSamples(duckFadeMs))
            subGain.setNow(0f)
            subGain.rampTo(1f, msToSamples(150))
            s to ++subGen
        }
        try {
            sub.playTrack(track)
        } catch (e: Exception) {
            log.warn("overlay play failed on guild {}", guildId, e)
            finishOverlay()
            throw e
        }
        val stillCurrent = synchronized(lock) { subGen == gen && overlayActive && subTrack === track }
        if (!stillCurrent) {
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("superseded overlay stop failed on guild {}", guildId, e)
            }
            siphon.clear()
            if (synchronized(lock) { overlayActive && subTrack === track }) {
                finishOverlay()
            }
            throw MixerBusyException("overlay cancelled during start on guild $guildId")
        }
        log.info("overlay '{}' started on guild {}", track.info.title, guildId)
        notifyMixerEvent("announceStart") {
            if (announcementId != null) put("announcementId", announcementId)
            putTrack(track)
        }
        return track
    }

    /**
     * Starts a `mode=parallel` overlay on its own secondary player, mixed
     * into the main chain alongside (but independent of) the crossfade sub.
     * Up to [maxParallelOverlays] run at once; music ducking is handled by
     * the shared [mainGain], so the first parallel overlay ducks and the
     * last one to finish releases it.
     */
    private fun startParallelOverlay(
        track: AudioTrack,
        duck: Float,
        announcementId: Long?,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
    ) {
        val slot = synchronized(lock) {
            if (parallelOverlays.size >= maxParallelOverlays) {
                throw MixerBusyException("parallel overlay limit ($maxParallelOverlays) reached on guild $guildId")
            }
            val id = announcementId ?: announcementQueue.nextId()
            val player = playerManager.createPlayer()
            val siphon = SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())
            player.setFilterFactory(SiphonFactory(siphon))
            player.addListener(parallelOverlayListener(id))
            val s = OverlaySlot(id, player, siphon, channel)
            parallelOverlays[id] = s
            s.track = track
            s.active = true
            s.ramp.setNow(0f)
            s.ramp.rampTo(1f, msToSamples(150))
            publishParallelOverlaysLocked()
            // First parallel overlay (or a parallel overlay while music is
            // not otherwise ducked) ducks the music.
            mainGain.rampTo(duck.coerceIn(0f, 1f), msToSamples(duckFadeMs))
            s
        }
        try {
            slot.player.playTrack(track)
        } catch (e: Exception) {
            log.warn("parallel overlay play failed on guild {}", guildId, e)
            finishParallelOverlay(slot.id)
            throw e
        }
        log.info("parallel overlay '{}' (id {}) started on guild {}", track.info.title, slot.id, guildId)
        notifyMixerEvent("announceStart") {
            put("announcementId", slot.id)
            put("mode", "parallel")
            putTrack(track)
        }
    }

    fun cancelOverlay(): Boolean {
        val sub = synchronized(lock) {
            if (!overlayActive) return false
            subGen++
            subPlayer
        }
        try {
            sub?.stopTrack()
        } finally {
            finishOverlay()
        }
        return true
    }

    fun destroy() {
        val (sub, parallels) = synchronized(lock) {
            pendingNextTrack = null
            readyNextTrack = null
            deferredWarmup = null
            overlapPreparation = null
            protocol.clearArmedLocked()
            subTrack = null
            xfadeActive = false
            activeRecipe = null
            plannedRecipe = null
            overlayActive = false
            analysisState.active = false
            siphonFactory?.resetSpeed()
            val p = parallelOverlays.values.toList()
            parallelOverlays.clear()
            publishParallelOverlaysLocked()
            subPlayer.also { subPlayer = null } to p
        }
        try {
            sub?.destroy()
        } catch (e: Exception) {
            log.warn("sub destroy failed on guild {}", guildId, e)
        }
        analysisService.destroy()
        TransitionMemory.global.clear(guildId)
        for (slot in parallels) {
            try {
                slot.player.destroy()
            } catch (e: Exception) {
                log.warn("parallel overlay destroy failed on guild {}", guildId, e)
            }
        }
        protocol.shutdown()
    }

    // ---- internals (all called under lock) ----

    /**
     * How much decoded audio Lavaplayer pre-buffers into a track's frame
     * buffer before anyone pulls a frame. The siphon has to be able to hold
     * all of it, or the first seconds of an overlaid track are dropped the
     * instant playTrack() returns. Guarded because tests build mixers over
     * stub managers.
     */
    private fun safeFrameBufferDurationMs(): Int = try {
        playerManager.frameBufferDuration
    } catch (t: Throwable) {
        DEFAULT_FRAME_BUFFER_MS
    }

    /**
     * A provide() frame must hold one whole encoded chunk of the *manager's*
     * output format. Sizing it from DISCORD_OPUS while the server emits PCM
     * overflows on every frame, [drain] then logs and drops each attempt, the
     * secondary decoder never advances — and the crossfade degenerates into
     * "the old track plays out, the new one starts after". Also guarded:
     * tests build mixers over stub managers.
     */
    private fun provideBufferSize(): Int = try {
        playerManager.configuration?.outputFormat?.maximumChunkSize()
            ?: StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    } catch (t: Throwable) {
        StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    }

    /** Allocates a separate buffer while pre-filling the secondary decoder. */
    private fun newSubProvideFrame(): MutableAudioFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    /** Pulls a decoded frame from the live secondary player during prefill. */
    private fun pumpSubFrame(sub: AudioPlayer, frame: MutableAudioFrame): Boolean = try {
        sub.provide(frame)
    } catch (e: Exception) {
        log.warn("secondary decoder prefill pump failed on guild {}", guildId, e)
        false
    }


    private fun ensureSubLocked(): AudioPlayer {
        var sub = subPlayer
        if (sub == null) {
            sub = playerManager.createPlayer()
            val factory = SiphonFactory(siphon, enableTimescale = true)
            siphonFactory = factory
            sub.setFilterFactory(factory)
            sub.addListener(subListener)
            subPlayer = sub
        }
        return sub
    }


    private fun prepareOverlap(plan: CrossfadePlan, fence: TransitionFence, analysisLog: String) {
        synchronized(lock) {
            if (overlapPreparation != null || overlayActive || xfadeActive || !matchesFenceLocked(fence)) return
            overlapPreparation = fence
        }
        protocol.executeAction {
            try {
                startOverlap(plan, fence, analysisLog)
            } catch (e: Exception) {
                log.warn("overlap preparation failed on guild {}", guildId, e)
            } finally {
                synchronized(lock) { if (overlapPreparation === fence) overlapPreparation = null }
            }
        }
    }

    private fun startOverlap(plan: CrossfadePlan, expected: TransitionFence, analysisLog: String) {
        data class OverlapSetup(val next: AudioTrack, val epoch: Long, val seq: Long, val revision: Long, val requestedStartMs: Long)
        data class StartedOverlap(val generation: Long, val startSample: Long, val beatMatched: Boolean, val fallbackReason: String?)
        // Keep the original queued until PCM is ready, so natural track end can still advance it.
        val (queued, epochAtStart, seqAtStart, revisionAtStart, requestedStartMs) = synchronized(lock) {
            if (!crossfadeEnabled || protocol.preserveTransition) return
            if (!matchesFenceLocked(expected)) return
            val n = readyNextTrack ?: pendingNextTrack ?: return
            OverlapSetup(n, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), slotRevision, protocol.armedStartPositionMs)
        }
        val next = queued.makeClone()
        if ((plan.beatMatched || plan.incomingStartMs > 0 || requestedStartMs > 0) && next.isSeekable) {
            val lead = if (silenceSkipEnabled) synchronized(lock) { leadForLocked(next) } else 0L
            val target = maxOf(lead, plan.incomingStartMs, requestedStartMs)
            if (target > 0L) {
                try {
                    val duration = next.duration
                    next.position = if (duration > 0) target.coerceIn(0, (duration - 500).coerceAtLeast(0)) else target
                    log.debug("beatmatch seek to {}ms on guild {}", target, guildId)
                } catch (e: Exception) {
                    log.warn("beatmatch seek failed on guild {}", guildId, e)
                }
            }
        } else {
            seekPastLead(next)
        }
        val incomingStartPosition = safePosition(next)
        // playTrack starts decoding asynchronously. Wait for captured PCM on the
        // action worker, keeping the shared scheduler and main PCM path running.
        val sub = synchronized(lock) {
            val s = ensureSubLocked()
            siphon.clear()
            if (plan.beatMatched) {
                siphonFactory?.configureTempo(plan.rateRatio.toDouble(), plan.overlapMs * SAMPLE_RATE / 1000, plan.recoveryMs * SAMPLE_RATE / 1000)
            } else {
                siphonFactory?.resetSpeed()
            }
            s
        }
        try {
            sub.playTrack(next)
        } catch (e: Exception) {
            log.warn("overlap pre-start play failed on guild {}", guildId, e)
        }
        val ready = prefillSubSiphon(sub) {
            synchronized(lock) { crossfadeEnabled && !protocol.preserveTransition && matchesFenceLocked(expected) && !overlayActive && !xfadeActive }
        }
        val started = synchronized(lock) {
            val startSample = maxOf(plan.startMs * SAMPLE_RATE / 1000, chainPos.get())
            if (!ready || !crossfadeEnabled || protocol.preserveTransition || overlayActive || xfadeActive ||
                startSample >= plan.handoffMs * SAMPLE_RATE / 1000 || !matchesFenceLocked(expected) ||
                protocol.gate.lastKnownEpoch != epochAtStart || protocol.advanceSeq.get() != seqAtStart ||
                slotRevision != revisionAtStart || protocol.playbackState != PlaybackState.ARMED) {
                if (!xfadeActive) siphonFactory?.resetSpeed()
                null
            } else {
                if (readyNextTrack === queued) readyNextTrack = null
                if (pendingNextTrack === queued) pendingNextTrack = null
                val nativeFailed = siphonFactory?.nativeFailed == true
                if (plan.beatMatched && nativeFailed) {
                    siphonFactory?.resetSpeed()
                }
                xfadeBeatMatched = plan.beatMatched && !nativeFailed
                xfadeFallbackReason = if (plan.beatMatched && nativeFailed) "timescale_native_failed" else plan.fallbackReason

                subTrack = next
                overlayActive = false
                xfadeTransitionKind = if (nativeFailed) "crossfade" else plan.transitionKind
                xfadeDecisionReason = if (nativeFailed) "timescale_native_failed" else plan.reason
                xfadeHandoffMs = plan.handoffMs
                xfadeMainTrack = main?.audioPlayer?.playingTrack
                subOutputSamples.set(0L)
                subSourceMilliSamples.set(0L)
                subStartPositionMs = incomingStartPosition
                subStartMs = System.currentTimeMillis()
                xfadeTotal = msToSamples(plan.durationMs).coerceAtLeast(1)
                xfadeLeft = xfadeTotal
                xfadeStartSample = startSample
                xfadeEndSample = plan.handoffMs * SAMPLE_RATE / 1000
                xfadeFadeEndSample = maxOf(startSample + 1, (plan.startMs + plan.overlapMs) * SAMPLE_RATE / 1000)
                // Sub-track normalization gain: applied on top of the sin curve.
                activeRecipe = plannedRecipe
                plannedRecipe?.let { TransitionMemory.global.record(guildId, it.strategy) }
                subNormGain = if (normalizationEnabled) normGainForLocked(next) else 1f
                if (startSample > plan.startMs * SAMPLE_RATE / 1000) {
                    log.debug(
                        "crossfade on guild {} armed {}ms after its window opened " +
                            "(chain at {}ms, window {}ms..{}ms); the fade starts late",
                        guildId, (startSample - plan.startMs * SAMPLE_RATE / 1000) * 1000L / SAMPLE_RATE,
                        chainPos.get() * 1000L / SAMPLE_RATE, plan.startMs, plan.handoffMs,
                    )
                }
                xfadeLogMs = 0L
                xfadeActive = true
                StartedOverlap(++subGen, startSample, xfadeBeatMatched, xfadeFallbackReason)
            }
        }
        if (started == null) {
            if (sub.playingTrack === next) {
                runCatching { sub.stopTrack() }.onFailure { log.warn("superseded overlap stop failed on guild {}", guildId, it) }
            }
            if (synchronized(lock) { !xfadeActive && !overlayActive }) siphon.clear()
            return
        }
        // A control command (override/invalidate/reset/arm) that aborted the fade
        // while playTrack was in flight bumps subGen and stops an empty sub —
        // the playTrack above then decodes a zombie into a torn-down slot.
        // Detect and unwind it: stop the decoder and only restore if not superseded by control plane.
        val stillCurrent = synchronized(lock) { subGen == started.generation && xfadeActive && subTrack === next }
        if (!stillCurrent) {
            log.warn("overlap superseded during start on guild {}; unwinding '{}'", guildId, titleOf(next))
            if (sub.playingTrack === next) {
                try {
                    sub.stopTrack()
                } catch (e: Exception) {
                    log.warn("superseded overlap stop failed on guild {}", guildId, e)
                }
            }
            synchronized(lock) {
                if (!xfadeActive || subTrack === next) {
                    siphonFactory?.resetSpeed()
                    xfadeBeatMatched = false
                    xfadeFallbackReason = null
                }
                // Only restore if the same arm epoch/seq is still active (e.g. transient sub contention),
                // never when superseded by invalidate/override/reset/re-arm.
                if (protocol.gate.lastKnownEpoch == epochAtStart && protocol.advanceSeq.get() == seqAtStart &&
                    slotRevision == revisionAtStart && protocol.playbackState == PlaybackState.ARMED) {
                    if (readyNextTrack == null) readyNextTrack = next
                    else if (pendingNextTrack == null) pendingNextTrack = next
                    else log.warn("dropping superseded overlap on guild {}: slot busy", guildId)
                }
            }
            if (synchronized(lock) { !xfadeActive && !overlayActive }) siphon.clear()
            return
        }
        val beatmatchLog = if (started.beatMatched) {
            "targetBpm=${plan.targetBpm} rateRatio=${plan.rateRatio} fadeBeats=${plan.fadeBeats}"
        } else "rateRatio=1.0"
        log.info(
            "crossfade guild={} from='{}' to='{}' beatMatched={} reason={} durationMs={} lateMs={} {} {}",
            guildId, expected.outgoing?.let { titleOf(it) }?.replace('\n', ' ')?.replace('\r', ' '),
            titleOf(next).replace('\n', ' ').replace('\r', ' '), started.beatMatched,
            started.fallbackReason ?: plan.reason ?: "none",
            (plan.handoffMs * SAMPLE_RATE / 1000 - started.startSample) * 1000 / SAMPLE_RATE,
            (started.startSample - plan.startMs * SAMPLE_RATE / 1000) * 1000 / SAMPLE_RATE,
            beatmatchLog, analysisLog,
        )
        val mainPosMs = main?.audioPlayer?.playingTrack?.let { safePosition(it) } ?: 0L
        notifyMixerEvent("overlapStart") {
            put("crossfadeMs", plan.durationMs)
            put("overlapMs", plan.overlapMs)
            put("recoveryMs", plan.recoveryMs)
            put("transitionKind", xfadeTransitionKind)
            xfadeDecisionReason?.let { put("decisionReason", it) }
            put("handoffInMs", (plan.handoffMs - mainPosMs).coerceAtLeast(0L))
            put("incomingStartMs", incomingStartPosition)
            put("beatMatched", xfadeBeatMatched)
            if (xfadeBeatMatched) {
                put("fadeBeats", plan.fadeBeats)
                put("targetBpm", plan.targetBpm)
                put("rateRatio", plan.rateRatio)
            } else if (xfadeFallbackReason != null) {
                put("fallbackReason", xfadeFallbackReason)
            }
            putTrack(next)
        }
    }

    /** Waits for one second of PCM, or a fully decoded short track, before fading. */
    private fun prefillSubSiphon(sub: AudioPlayer, current: () -> Boolean): Boolean {
        val frame = newSubProvideFrame()
        try {
            var burst = 0
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (burst < 600 && System.nanoTime() < deadline && current()) {
                if (siphon.pendingSamples >= SAMPLE_RATE) return true
                if (sub.playingTrack == null) return siphon.pendingSamples > 0
                if (pumpSubFrame(sub, frame)) burst++ else Thread.sleep(5)
            }
            return siphon.pendingSamples >= SAMPLE_RATE && current()
        } finally {
            frame.setBuffer(java.nio.ByteBuffer.allocate(0))
        }
    }

    private fun notifyMixerEvent(type: String, body: JsonObjectBuilder.() -> Unit) =
        sendMixerEvent(main, guildId, type) {
            if (type == "MixerAdvancedEvent" || type == "overlapStart" || type == "overlapEnd") {
                val status = transitionStatus
                put("transitionKind", status.kind)
                status.reason?.let { put("decisionReason", it) }
            }
            body()
        }

    private data class Handoff(
        val source: AudioTrack,
        val clone: AudioTrack,
        val encodedTrack: String?,
        val outputPosition: Long,
        val subReportedMs: Long,
        val subOutMs: Long,
        val droppedFrames: Long,
    )

    private fun doHandoff() {
        val handoffAllowed = synchronized(lock) {
            val armed = xfadeMainTrack ?: return@synchronized true
            val playing = main?.audioPlayer?.playingTrack
            // Lavaplayer nulls playingTrack before dispatching FINISHED/LOAD_FAILED,
            // so a handoff driven by onTrackEnd legitimately sees no playing track;
            // lastEndedMain keeps that just-ended instance recognizable for a short
            // window. Anything else means the overlap was armed on a track that was
            // really replaced — never hand off into it.
            val endedRecently = lastEndedMain === armed &&
                System.currentTimeMillis() - lastEndedMainAt <= LAST_ENDED_MAIN_GRACE_MS
            playing === armed || endedRecently
        }
        if (!handoffAllowed) {
            // Callers decide under the lock and act after releasing it, so a
            // client replace can slip in between; never hand off into a track
            // the overlap was not armed on. Re-evaluate the slot so a stuck
            // main track still advances instead of stalling silent.
            abortXfade("handoff arrived after the armed main track was replaced")
            fallbackAdvanceOrIdle()
            return
        }
        data class Plan(
            val handoff: Handoff?,
            val epochAtStart: Long,
            val seqAtStart: Long,
            val wasBeatMatched: Boolean,
            val fallbackReason: String?,
        )
        val plan = synchronized(lock) {
            // poll(), mainListener.onTrackEnd and recoverFromMainStall all read
            // xfadeActive under the lock and then call us *after* releasing it.
            // A main track hitting FINISHED at the exact handoff position can
            // therefore race the position-based handoff; without this guard the
            // second caller replays the whole transition and throws away the
            // mask ramp it just armed.
            if (!xfadeActive) return@doHandoff
            refreshBeatmatchStatusLocked()
            val wasBeatMatched = xfadeBeatMatched
            val fallbackReason = xfadeFallbackReason
            xfadeBeatMatched = false
            xfadeFallbackReason = null
            val sub = subTrack
            val armedEnc = protocol.armedEncodedTrack
            protocol.armedTrack = null
            protocol.armedEncodedTrack = null
            protocol.armedStartPositionMs = 0L
            protocol.armedOutgoingEndPositionMs = 0L
            protocol.armedOutgoingTrack = null
            xfadeActive = false
            activeRecipe = null
            plannedRecipe = null
            // of playback: the state must reflect audio that keeps flowing.
            protocol.playbackState = PlaybackState.PLAYING
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
            xfadeFadeEndSample = -1L
            xfadeMainTrack = null
            val epochAtStart = protocol.gate.lastKnownEpoch
            val seqAtStart = protocol.advanceSeq.get()
            if (sub == null) {
                resetGainsLocked()
                Plan(null, epochAtStart, seqAtStart, wasBeatMatched, fallbackReason)
            } else {
                val clone = sub.makeClone()
                val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
                val subSourceMs = subSourceSamples() * 1000L / SAMPLE_RATE
                Plan(
                    Handoff(
                        source = sub,
                        clone = clone,
                        encodedTrack = armedEnc,
                        outputPosition = subStartPositionMs + subSourceMs,
                        subReportedMs = safePosition(sub),
                        subOutMs = subOutMs,
                        droppedFrames = siphon.droppedFrames,
                    ),
                    epochAtStart,
                    seqAtStart,
                    wasBeatMatched,
                    fallbackReason,
                )
            }
        }
        val handoff = plan.handoff ?: run {
            // sub==null while xfadeActive was true: inconsistent arming. Do not
            // leave the state machine PLAYING with nothing queued.
            fallbackAdvanceOrIdle()
            return
        }
        try {
            if (handoff.clone.isSeekable) {
                val duration = handoff.clone.duration
                handoff.clone.position = if (duration > 0) {
                    handoff.outputPosition.coerceIn(0, duration)
                } else {
                    handoff.outputPosition.coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            log.warn("handoff seek failed on guild {}, starting from 0", guildId, e)
        }
        val sub = synchronized(lock) { subPlayer }
        synchronized(playerMutationLock) {
            // Re-check under lock inside playerMutationLock: if an override/invalidate/reset
            // arrived while seeking or waiting for playerMutationLock, abort before touching
            // mainGain or replacing the new main track.
            val committedSeq = synchronized(lock) {
                if (protocol.gate.lastKnownEpoch != plan.epochAtStart || protocol.advanceSeq.get() != plan.seqAtStart) {
                    if (subTrack === handoff.source) subTrack = null
                    resetGainsLocked()
                    null
                } else {
                    if (subTrack === handoff.source) subTrack = null
                    masking = true
                    maskingTrack = handoff.clone
                    protocol.lastCountedTrack = handoff.clone
                    protocol.advanceSeq.incrementAndGet()
                }
            }
            if (committedSeq == null) {
                try {
                    sub?.stopTrack()
                } catch (e: Exception) {
                    log.warn("sub stop failed on guild {}", guildId, e)
                }
                siphonFactory?.resetSpeed()
                siphon.clear()
                log.warn("crossfade handoff superseded on guild {}; dropping '{}'", guildId, titleOf(handoff.source))
                return
            }
            try {
                sub?.stopTrack()
            } catch (e: Exception) {
                log.warn("sub stop failed on guild {}", guildId, e)
            }
            siphonFactory?.resetSpeed()
            siphon.clear()
            subGain.setNow(0f)
            mainGain.setNow(0f)
            mainGain.rampTo(1f, msToSamples(maskMs).coerceAtLeast(1))
            // Handoff carries the sub track's normalization gain over to the
            // main slot so the incoming track keeps its level through the mask.
            mainNormGain = subNormGain
            subNormGain = 1f
            tailCutter.reset()
            val mainPlayer = main
            if (mainPlayer == null) {
                synchronized(lock) {
                    masking = false
                    maskingTrack = null
                    resetGainsLocked()
                }
                fallbackAdvanceOrIdle()
                return
            }
            try {
                mainPlayer.play(handoff.clone)
                log.debug(
                    "crossfade handoff on guild {} at {}ms (sub decoder was at {}ms, " +
                        "{}ms of sub audio heard, {} frames dropped)",
                    guildId, handoff.outputPosition, handoff.subReportedMs, handoff.subOutMs, handoff.droppedFrames,
                )
                notifyMixerEvent("overlapEnd") {
                    put("reason", "handoff")
                    put("beatMatched", plan.wasBeatMatched)
                    if (!plan.wasBeatMatched && plan.fallbackReason != null) {
                        put("fallbackReason", plan.fallbackReason)
                    }
                    putTrack(handoff.source)
                }
                notifyAdvanced("CROSSFADE", plan.epochAtStart, committedSeq, handoff.encodedTrack)
            } catch (e: Exception) {
                log.warn("handoff play failed on guild {}", guildId, e)
                synchronized(lock) {
                    masking = false
                    maskingTrack = null
                    resetGainsLocked()
                }
                fallbackAdvanceOrIdle()
            }
        }
    }

    /** After an aborted/stale handoff, fall back to advancing the slot or idle. */
    private fun fallbackAdvanceOrIdle() {
        val hasNext = synchronized(lock) { readyNextTrack != null || pendingNextTrack != null }
        if (hasNext) {
            advanceNext()
        } else {
            synchronized(lock) {
                if (main?.audioPlayer?.playingTrack == null) {
                    protocol.playbackState = PlaybackState.IDLE
                }
            }
        }
    }

    private fun abortXfade(reason: String) {
        var wasBeatMatched = false
        var fallbackReason: String? = null
        val (sub, armedSub, subName) = synchronized(lock) {
            // Idempotent: nothing armed means a duplicate/late abort (e.g. a
            // deliberate sub.stopTrack() racing the listener) must not replay
            // teardown, clobber a handoff mask ramp, or spam overlapEnd.
            if (!xfadeActive && subTrack == null) return@abortXfade
            refreshBeatmatchStatusLocked()
            wasBeatMatched = xfadeBeatMatched
            fallbackReason = xfadeFallbackReason
            xfadeBeatMatched = false
            xfadeFallbackReason = null
            val subPosMs = subTrack?.let { safePosition(it) } ?: -1L
            val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
            val elapsed = if (subStartMs > 0) System.currentTimeMillis() - subStartMs else -1L
            val name = subTrack?.let { titleOf(it) } ?: "-"
            log.warn(
                "crossfade aborted on guild {} after {}ms: {} " +
                    "(sub '{}' subPos={}ms subOut={}ms pending={}f dropped={}f)",
                guildId, elapsed, reason, name, subPosMs, subOutMs,
                siphon.pendingFrames, siphon.droppedFrames,
            )
            xfadeActive = false
            activeRecipe = null
            plannedRecipe = null
            xfadeStartSample = -1L
            xfadeEndSample = -1L
            xfadeFadeEndSample = -1L
            xfadeMainTrack = null
            val armed = subTrack
            subTrack = null
            protocol.armedHandoffId = null
            subNormGain = 1f
            subGen++
            siphon.clear()
            mainGain.rampTo(1f, msToSamples(duckFadeMs))
            subGain.setNow(0f)
            Triple(subPlayer, armed, name)
        }
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {} (was '{}')", guildId, subName, e)
        }
        siphonFactory?.resetSpeed()
        notifyMixerEvent("overlapEnd") {
            put("reason", "aborted")
            put("detail", reason)
            put("beatMatched", wasBeatMatched)
            fallbackReason?.let { put("fallbackReason", it) }
            putTrack(armedSub)
        }
    }

    private fun finishOverlay() {
        val sub = synchronized(lock) {
            if (!overlayActive) return
            overlayActive = false
            subTrack = null
            sequentialOverlayId = null
            sequentialOverlayChannel = AnnounceChannel.BOTH
            subNormGain = 1f
            siphon.clear()
            // Release the music duck only if no parallel overlays are still
            // holding it; otherwise the last parallel overlay will release it.
            if (parallelOverlays.isEmpty()) {
                mainGain.rampTo(1f, msToSamples(duckFadeMs))
            }
            subGain.setNow(0f)
            subPlayer
        }
        log.info("overlay finished on guild {}", guildId)
        notifyMixerEvent("announceEnd") {}
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", guildId, e)
        }
    }

    /** Tears down a `mode=parallel` overlay slot and releases the duck if it was the last. */
    private fun finishParallelOverlay(id: Long) {
        val slot = synchronized(lock) {
            val removed = parallelOverlays.remove(id) ?: return
            removed.active = false
            publishParallelOverlaysLocked()
            removed
        }
        log.info("parallel overlay (id {}) finished on guild {}", id, guildId)
        notifyMixerEvent("announceEnd") { put("announcementId", id) }
        try {
            slot.player.stopTrack()
        } catch (e: Exception) {
            log.warn("parallel overlay stop failed on guild {}", guildId, e)
        }
        synchronized(lock) {
            // Release the duck once no overlay (sequential or parallel) holds it.
            if (!overlayActive && parallelOverlays.isEmpty()) {
                mainGain.rampTo(1f, msToSamples(duckFadeMs))
            }
        }
        try {
            slot.player.destroy()
        } catch (e: Exception) {
            log.warn("parallel overlay player destroy failed on guild {}", guildId, e)
        }
    }

    /** Plays the queued track now, seeking past analyzed leading silence. */
    private fun advanceNext(expected: TransitionFence? = null) {
        data class GaplessPlan(val next: AudioTrack, val epochAtStart: Long, val seqAtStart: Long, val revision: Long, val encodedTrack: String?, val preserve: Boolean, val leadMs: Long?, val requestedStartMs: Long)
        val plan = synchronized(lock) {
            if (expected != null && !matchesFenceLocked(expected)) return
            val n = readyNextTrack ?: pendingNextTrack ?: return
            val trim = adaptiveTrim?.takeIf {
                it.incoming === n && (it.outgoing === main?.audioPlayer?.playingTrack ||
                    (main?.audioPlayer?.playingTrack == null && it.outgoing === lastEndedMain))
            }
            val enc = protocol.armedEncodedTrack
            val requestedStartMs = protocol.armedStartPositionMs
            readyNextTrack = null
            pendingNextTrack = null
            protocol.armedTrack = null
            protocol.armedEncodedTrack = null
            protocol.armedStartPositionMs = 0L
            protocol.armedOutgoingEndPositionMs = 0L
            protocol.armedOutgoingTrack = null
            // Consuming the slot is a transition, not an end: PLAYING even if
            // play() below fails (the failure path logs and falls back to IDLE).
            protocol.playbackState = PlaybackState.PLAYING
            lastMainTrack = null
            tailCutter.reset()
            // Inherit the analyzed normalization gain immediately before play
            // so the gapless transition doesn't reset the gain to 1.0f mid-stream.
            if (normalizationEnabled) {
                mainNormGain = normGainForLocked(n)
            }
            GaplessPlan(n, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), slotRevision, enc, protocol.preserveTransition, trim?.decision?.incomingStartMs, requestedStartMs)
        }
        seekPastLead(plan.next, plan.preserve, plan.leadMs, plan.requestedStartMs)
        synchronized(playerMutationLock) {
            val committedSeq = synchronized(lock) {
                if (protocol.gate.lastKnownEpoch != plan.epochAtStart || protocol.advanceSeq.get() != plan.seqAtStart ||
                    slotRevision != plan.revision) {
                    null
                } else {
                    preservedStartTrack = if (plan.preserve) plan.next else null
                    protocol.preserveTransition = false
                    adaptiveTrim = null
                    protocol.lastCountedTrack = plan.next
                    protocol.advanceSeq.incrementAndGet()
                }
            }
            if (committedSeq == null) {
                log.warn("gapless advance superseded on guild {}; dropping '{}'", guildId, titleOf(plan.next))
                return
            }
            val mainPlayer = main
            if (mainPlayer == null) {
                fallbackAdvanceOrIdle()
                return
            }
            try {
                mainPlayer.play(plan.next)
                log.info("gapless advance on guild {}: now '{}'", guildId, titleOf(plan.next))
                notifyAdvanced("GAPLESS", plan.epochAtStart, committedSeq, plan.encodedTrack)
            } catch (e: Exception) {
                log.warn("gapless play failed on guild {}", guildId, e)
                fallbackAdvanceOrIdle()
            }
        }
    }

    private fun seekPastLead(track: AudioTrack, preserve: Boolean = false, plannedLeadMs: Long? = null, requestedStartMs: Long = 0L) {
        val lead = maxOf(requestedStartMs, if (preserve) 0L else synchronized(lock) { plannedLeadMs ?: if (silenceSkipEnabled) leadForLocked(track) else 0L })
        if (lead > 0 && track.isSeekable) {
            try {
                val duration = track.duration
                track.position = if (duration > 0) lead.coerceIn(0, (duration - 500).coerceAtLeast(0)) else lead
                log.debug("skipped {}ms leading silence on guild {}", lead, guildId)
            } catch (e: Exception) {
                log.warn("lead skip seek failed on guild {}", guildId, e)
            }
        }
    }

    private fun resetGainsLocked() {
        mainGain.setNow(1f)
        subGain.setNow(0f)
    }

    // ---- silence analysis ----

    private fun trimForLocked(track: AudioTrack): TrimPoints = AnalysisCaches.trimFor(track)

    private fun leadForLocked(track: AudioTrack): Long = trimForLocked(track).leadMs
    private fun trailForLocked(track: AudioTrack): Long = trimForLocked(track).trailMs

    private fun normGainForLocked(track: AudioTrack): Float =
        AnalysisCaches.normGainFor(track, targetLufs, normGainBaseline)
    fun peekNextTrim(): TrimPoints? = synchronized(lock) {
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        trimForLocked(next).takeIf { it != TrimPoints.NONE }
    }

    data class CurrentTempo(val identifier: String, val bpm: Float)

    data class TrackKey(val identifier: String, val analysis: KeyAnalysis)

    /** Identity and estimate are captured together; an unknown estimate still represents a completed scan. */
    fun currentKey(): TrackKey? = synchronized(lock) {
        if (!keyDetectionEnabled) return null
        val track = main?.audioPlayer?.playingTrack ?: return null
        val result = idOf(track)?.let { AnalysisCaches.key[it] } ?: return null
        TrackKey(track.info.identifier, result)
    }

    fun peekNextKey(): TrackKey? = synchronized(lock) {
        if (!keyDetectionEnabled) return null
        val track = readyNextTrack ?: pendingNextTrack ?: return null
        val result = idOf(track)?.let { AnalysisCaches.key[it] } ?: return null
        TrackKey(track.info.identifier, result)
    }

    fun currentTempo(): CurrentTempo? = synchronized(lock) {
        if (!bpmDetectionEnabled) return null
        val track = main?.audioPlayer?.playingTrack ?: return null
        val id = idOf(track) ?: return null
        val bpm = AnalysisCaches.bpmHead[id]?.bpm ?: AnalysisCaches.bpmTail[id]?.bpm ?: AnalysisCaches.bpm[id]?.bpm ?: return null
        if (!bpm.isFinite() || bpm <= 0f) return null
        CurrentTempo(track.info.identifier, bpm)
    }

    /** Cached tempo of the queued next track; null while detection is pending or disabled. */
    fun peekNextBpm(): BpmDetector.BpmResult? = synchronized(lock) {
        if (!bpmDetectionEnabled) return null
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        val id = idOf(next) ?: return null
        AnalysisCaches.bpm[id]
    }

    fun peekNextBeatGrid(): BeatGrid? = synchronized(lock) {
        if (!bpmDetectionEnabled) return null
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        val id = idOf(next) ?: return null
        AnalysisCaches.bpmHead[id]
    }

    /** Moves pending → ready once its trim and gain are known (or no analysis needed). */
    private fun promoteIfPendingLocked(expectedId: String? = null) {
        val pending = pendingNextTrack ?: return
        // A finished analysis must only promote the track it analyzed: a late
        // queueNext/arm can replace pendingNextTrack mid-scan, and promoting the
        // replacement with the stale scan's identity would hand the mixer a
        // mismatched (or partially-cached) track.
        if (expectedId != null && idOf(pending) != expectedId) return
        val id = idOf(pending) ?: run {
            pendingNextTrack = null
            readyNextTrack = pending
            return
        }
        val silenceDone = !silenceSkipEnabled || AnalysisCaches.trim.contains(id)
        val normDone = !normalizationEnabled || AnalysisCaches.norm.contains(id)
        if (silenceDone && normDone) {
            pendingNextTrack = null
            readyNextTrack = pending
        }
    }

    /** Forced promotion when we must advance despite incomplete analysis. */
    private fun promoteForcedLocked() {
        val pending = pendingNextTrack ?: return
        pendingNextTrack = null
        if (readyNextTrack == null) readyNextTrack = pending
    }


    /**
     * Schedules a background loudness scan of the currently playing track so
     * its measured LUFS lands in the shared cache (and, if it is still the
     * playing track, its [mainNormGain] updates immediately). Runs on the
     * dedicated analysis decoder, never the live sub player.
     */
    internal fun scheduleBaselineAnalysis(track: AudioTrack) {
        synchronized(lock) {
            if (normGainBaseline != 0f || analysisState.active) return
            analysisState.active = true
            analysisService.beginScan()
        }
        analysisExecutor.execute {
            val clone = try {
                track.makeClone()
            } catch (e: Exception) {
                synchronized(lock) { analysisState.active = false }
                drainDeferredWarmup()
                return@execute
            }
            try {
                val loudness = analysisService.measureBaseline(clone)
                if (loudness != null) {
                    val trackId = idOf(track) ?: track.identifier
                    AnalysisCaches.norm[trackId] = loudness
                    synchronized(lock) {
                        val playing = main?.audioPlayer?.playingTrack
                        if (normalizationEnabled && playing != null && idOf(playing) == trackId) {
                            mainNormGain = normGainForLocked(playing)
                        }
                    }
                    log.info("normalization baseline guild {}: lufs={}", guildId, loudness)
                }
            } catch (e: Exception) {
                log.warn("baseline loudness scan failed on guild {}", guildId, e)
            } finally {
                synchronized(lock) { analysisState.active = false }
                drainDeferredWarmup()
            }
        }
    }

    fun maybeRetryAnalysis(): AudioTrack? {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            if (analysisQueued) return null
            val mainPlaying = main?.audioPlayer?.playingTrack
            val candidates = listOfNotNull(
                pendingNextTrack,
                readyNextTrack,
                if (bpmDetectionEnabled || keyDetectionEnabled || silenceSkipEnabled) mainPlaying else null,
            )
            val pending = candidates.firstOrNull { candidate ->
                if (!candidate.isSeekable || candidate.duration <= 0) return@firstOrNull false
                val id = idOf(candidate) ?: return@firstOrNull false
                val needSilence = silenceSkipEnabled && !AnalysisCaches.trim.contains(id)
                val needNorm = candidate !== mainPlaying && normalizationEnabled && !AnalysisCaches.norm.contains(id)
                val needBpm = bpmDetectionEnabled && needsBpmAnalysisLocked(id)
                val needKey = keyDetectionEnabled && !AnalysisCaches.key.contains(id)
                (needSilence || needNorm || needBpm || needKey) && analysisState.canRetryLocked(id, now)
            } ?: return null
            log.debug("retrying deferred analysis for '{}' on guild {}", titleOf(pending), guildId)
            try {
                pending.makeClone()
            } catch (e: Exception) {
                log.debug("analysis retry clone failed on guild {}: {}", guildId, e.toString())
                null
            }
        }
    }

    /** Bounded, cache-only warmup; it never arms a queue slot or mutates playback. */
    fun requestPreanalysis(track: AudioTrack): Boolean {
        val id = idOf(track) ?: return false
        val includeSilence = silenceSkipEnabled
        synchronized(lock) {
            val needSilence = includeSilence && !AnalysisCaches.trim.contains(id)
            val needBpm = bpmDetectionEnabled && needsBpmAnalysisLocked(id)
            val needKey = keyDetectionEnabled && !AnalysisCaches.key.contains(id)
            if ((!needSilence && !needBpm && !needKey) || !track.isSeekable || track.duration <= 0) return false
            if (analysisQueued || analysisState.active) {
                deferredWarmup = track
                return true
            }
            analysisQueued = true
            deferredWarmup = null
        }
        return scheduleWarmup(track)
    }

    private fun scheduleWarmup(track: AudioTrack): Boolean {
        return try {
            analysisExecutor.execute {
                try { analyzeBlocking(track, cacheOnly = true, includeSilence = silenceSkipEnabled) }
                catch (e: Exception) { log.warn("preanalysis failed on guild {}", guildId, e) }
                finally {
                    synchronized(lock) { analysisQueued = false }
                    drainDeferredWarmup()
                }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) { analysisQueued = false }
            false
        }
    }

    private fun drainDeferredWarmup() {
        val next = synchronized(lock) {
            if (analysisQueued || analysisState.active) return
            deferredWarmup?.also {
                deferredWarmup = null
                analysisQueued = true
            }
        }
        if (next != null) scheduleWarmup(next)
    }

    private fun needsBpmAnalysisLocked(identifier: String): Boolean =
        analysisState.completedBpmIdentifier != identifier &&
            !(AnalysisCaches.bpm.contains(identifier) && AnalysisCaches.bpmHead.contains(identifier) && AnalysisCaches.bpmTail.contains(identifier) && AnalysisCaches.featuresHead.contains(identifier) && AnalysisCaches.featuresTail.contains(identifier))

    /**
     * True once the incoming track's beatmatching inputs (BPM grid + transition
     * features) are cached, or when beatmatching never needs them. A late arm
     * can leave analysis running while the fade window opens; the poll loop
     * uses this to wait for the scan instead of firing a plain fade with
     * `missing_features`.
     */
    private fun incomingFeaturesReadyLocked(track: AudioTrack): Boolean {
        if (!beatmatchEnabled || !bpmDetectionEnabled) return true
        val id = idOf(track) ?: return true
        return !needsBpmAnalysisLocked(id)
    }

    /** Scans a clone on the isolated analysis player. A concurrent scan defers this work. */
    fun analyzeBlocking(track: AudioTrack, cacheOnly: Boolean = false, includeSilence: Boolean = !cacheOnly) {
        val doSilence = silenceSkipEnabled && includeSilence
        val doNorm = normalizationEnabled && !cacheOnly
        val doBpm = bpmDetectionEnabled
        val doKey = keyDetectionEnabled && track.isSeekable && track.duration in 1 until Long.MAX_VALUE / 2
        if (!doSilence && !doNorm && !doBpm && !doKey) {
            if (!cacheOnly) synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        val identifier = idOf(track) ?: run {
            if (!cacheOnly) synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        synchronized(lock) {
            val hasSilence = !doSilence || AnalysisCaches.trim.contains(identifier)
            val hasNorm = !doNorm || AnalysisCaches.norm.contains(identifier)
            val hasBpm = !doBpm || !needsBpmAnalysisLocked(identifier)
            val hasKey = !doKey || AnalysisCaches.key.contains(identifier)
            if (hasSilence && hasNorm && hasBpm && hasKey) {
                if (!cacheOnly) promoteIfPendingLocked(identifier)
                return
            }
            if (analysisState.active) {
                // A queued cache-only warmup may reach this gate after a normal scan starts.
                // Retain it; the scan owner or warmup worker will drain it when idle.
                if (cacheOnly && deferredWarmup == null) deferredWarmup = track
                log.debug(
                    "analysis deferred on guild {}: another scan is running; will retry",
                    guildId,
                )
                return
            }
            analysisState.active = true
            analysisService.beginScan()
        }
        try {
            val hasSilence = doSilence && !AnalysisCaches.trim.contains(identifier)
            val hasNorm = doNorm && !AnalysisCaches.norm.contains(identifier)
            val needBpm = doBpm && synchronized(lock) { needsBpmAnalysisLocked(identifier) }
            val needKey = doKey && !AnalysisCaches.key.contains(identifier)
            val result = analysisService.analyze(
                track,
                TrackAnalysisSettings(
                    silence = hasSilence,
                    normalization = hasNorm,
                    bpm = needBpm,
                    silenceThresholdDb = silenceThresholdDb,
                    silenceMinSoundMs = silenceMinSoundMs,
                    silenceHeadScanMs = silenceHeadScanMs,
                    silenceTailScanMs = silenceTailScanMs,
                    bpmScanMs = bpmScanMs,
                    key = needKey,
                    keyScanMs = keyScanMs,
                ),
            )
            if (result.aborted || result.featuresHead?.unknownReason == "incomplete_window" ||
                result.featuresTail?.unknownReason == "incomplete_window") {
                synchronized(lock) { analysisState.noteFailureLocked(identifier) }
                return
            }
            if (hasNorm && result.loudnessRms != null) {
                AnalysisCaches.norm[identifier] = result.loudnessRms
                applyAnalyzedNormGain(identifier)
                log.info("normalization guild {}: lufs={}", guildId, result.loudnessRms)
            }
            if (needKey && result.key != null) AnalysisCaches.key[identifier] = result.key
            if (needBpm && result.bpm != null) {
                AnalysisCaches.bpm[identifier] = result.bpm
                log.info(
                    "bpm analysis guild {}: bpm={} firstBeat={}ms confidence={}",
                    guildId,
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
            if (doBpm && !result.aborted) {
                val playing = synchronized(lock) { main?.audioPlayer?.playingTrack }
                val playingId = playing?.let { idOf(it) }
                if (playing != null && playingId != null && !AnalysisCaches.featuresTail.contains(playingId)) {
                    val tail = analysisService.measureTailAnalysis(playing, bpmScanMs)
                    if (tail?.features?.unknownReason != "incomplete_window") {
                        tail?.grid?.let { AnalysisCaches.bpmTail[playingId] = it }
                        tail?.features?.let { AnalysisCaches.featuresTail[playingId] = it }
                    }
                }
            }
            if (doSilence && !result.aborted) {
                val playing = synchronized(lock) { main?.audioPlayer?.playingTrack }
                val playingId = playing?.let { idOf(it) }
                if (playing != null && playingId != null && playingId != identifier && !AnalysisCaches.trim.contains(playingId)) {
                    val playingTrim = analysisService.measureTailSilence(
                        playing,
                        TrackAnalysisSettings(
                            silence = true,
                            normalization = false,
                            bpm = false,
                            silenceThresholdDb = silenceThresholdDb,
                            silenceMinSoundMs = silenceMinSoundMs,
                            silenceHeadScanMs = silenceHeadScanMs,
                            silenceTailScanMs = silenceTailScanMs,
                            bpmScanMs = bpmScanMs,
                        ),
                    )
                    if (playingTrim != null) {
                        AnalysisCaches.trim[playingId] = playingTrim
                    }
                }
            }
            synchronized(lock) {
                if (needBpm) analysisState.completedBpmIdentifier = identifier
                if (hasSilence) {
                    AnalysisCaches.trim[identifier] = result.trim ?: TrimPoints.NONE
                }
                analysisState.clearFailureIfMatchingLocked(identifier)
                if (!cacheOnly) promoteIfPendingLocked(identifier)
            }
            if (hasSilence) {
                log.info(
                    "silence analysis guild {}: lead={}ms trail={}ms",
                    guildId,
                    result.trim?.leadMs ?: 0,
                    result.trim?.trailMs ?: 0,
                )
            }
        } catch (e: Exception) {
            log.warn("silence analysis failed on guild {}", guildId, e)
            synchronized(lock) { analysisState.noteFailureLocked(identifier) }
        } finally {
            synchronized(lock) { analysisState.active = false }
            drainDeferredWarmup()
        }
    }

    private fun applyAnalyzedNormGain(identifier: String) {
        synchronized(lock) {
            if (!normalizationEnabled) return
            val playing = main?.audioPlayer?.playingTrack
            if (playing != null && idOf(playing) == identifier) {
                mainNormGain = normGainForLocked(playing)
            }
            val activeSub = subTrack
            if (xfadeActive && activeSub != null && idOf(activeSub) == identifier) {
                subNormGain = normGainForLocked(activeSub)
            }
        }
    }


    private fun safePosition(track: AudioTrack): Long = try {
        track.position
    } catch (e: Exception) {
        lastPosition
    }

    // ==========================================
    // ====== v2 Core Protocol Implementation ===
    // ==========================================

    fun encodeTrackBase64(track: AudioTrack): String = protocol.encodeTrackBase64(track)

    fun arm(
        epoch: Long,
        idemKey: String,
        track: AudioTrack?,
        isEmpty: Boolean,
        rawEncodedTrack: String? = null,
        preserveTransition: Boolean = false,
        startPositionMs: Long? = null,
        outgoingEndPositionMs: Long? = null,
    ): ArmResult = protocol.arm(epoch, idemKey, track, isEmpty, rawEncodedTrack, preserveTransition, startPositionMs, outgoingEndPositionMs)

    fun invalidate(epoch: Long, idemKey: String): InvalidateResult = protocol.invalidate(epoch, idemKey)

    fun override(
        epoch: Long,
        idemKey: String,
        action: String,
        newTrack: AudioTrack?,
        rawEncodedTrack: String? = null,
    ): OverrideResult = protocol.override(epoch, idemKey, action, newTrack, rawEncodedTrack)

    fun reset(epoch: Long, idemKey: String, windowOpenGraceMs: Long = 1500L): ResetResult =
        protocol.reset(epoch, idemKey, windowOpenGraceMs)

    fun checkJitWindow() = protocol.checkJitWindow()

    fun notifyAdvanced(
        handoffType: String,
        expectedEpoch: Long = lastKnownEpoch,
        expectedSeq: Long = protocol.advanceSeq.get(),
        encodedOverride: String? = null,
    ) = protocol.notifyAdvanced(handoffType, expectedEpoch, expectedSeq, encodedOverride)

    companion object {
        private val log = LoggerFactory.getLogger(GuildMixer::class.java)
        private const val SAMPLE_RATE = 48000

        /** Lavaplayer's default frame buffer; used when the manager can't say. */
        private const val DEFAULT_FRAME_BUFFER_MS = 5000

        /**
         * How long a FINISHED/LOAD_FAILED main track counts as "the armed track,
         * just ended" for the handoff guard; see [lastEndedMain].
         */
        private const val LAST_ENDED_MAIN_GRACE_MS = 10_000L

        fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)
    }
}
