package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame
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
    private val settings = MixerSettings(config)
    private val deck = SubDeck(guildId, playerManager)
    private val lock = Any()
    /** Outer lock serializing main.play/main.stop across doHandoff, advanceNext, and override. Never acquired while holding [lock]. */
    private val playerMutationLock = Any()
    @Volatile var main: IPlayer? = null
        private set

    var crossfadeEnabled: Boolean by settings::crossfadeEnabled
    var crossfadeMs: Long by settings::crossfadeMs
    var fadeInMs: Long by settings::fadeInMs
    var duckLevel: Float by settings::duckLevel
    var duckFadeMs: Long by settings::duckFadeMs
    var maskMs: Long by settings::maskMs
    var handoffBridgeMs: Long by settings::handoffBridgeMs
    var maxParallelOverlays: Int by settings::maxParallelOverlays

    var normalizationEnabled: Boolean by settings::normalizationEnabled
    var targetLufs: Float by settings::targetLufs

    fun updateNormalization(enabled: Boolean, newTargetLufs: Float? = null) {
        var needsBaselineTrack: AudioTrack? = null
        synchronized(lock) {
            normalizationEnabled = enabled
            newTargetLufs?.let { targetLufs = it.coerceIn(-30.0f, -6.0f) }
            if (!enabled) {
                mainNorm.rampTo(1f, msToSamples(NORM_RAMP_MS))
                subNorm.rampTo(1f, msToSamples(NORM_RAMP_MS))
                return
            }
            val playing = mainTrack()
            if (playing != null) {
                knownNormGainLocked(playing)?.let { mainNorm.rampTo(it, msToSamples(NORM_RAMP_MS)) }
                val id = idOf(playing)
                if (normGainBaseline == 0f &&
                    !analysis.activeLocked &&
                    (id == null || !AnalysisCaches.norm.contains(id))
                ) {
                    needsBaselineTrack = playing
                }
            }
            val activeSub = deck.track
            if (xfadeActive && activeSub != null) {
                knownNormGainLocked(activeSub)?.let { subNorm.rampTo(it, msToSamples(NORM_RAMP_MS)) }
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
            if (enabled) mainTrack() else null
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
            if (enabled) mainTrack() else null
        }
        playing?.let { track ->
            runCatching { track.makeClone() }.getOrNull()?.let { requestPreanalysis(it) }
        }
    }

    var silenceSkipEnabled: Boolean by settings::silenceSkipEnabled
    var silenceThresholdDb: Float by settings::silenceThresholdDb
    var silenceMinSoundMs: Long by settings::silenceMinSoundMs
    var silenceHeadScanMs: Long by settings::silenceHeadScanMs
    var silenceTailScanMs: Long by settings::silenceTailScanMs
    /** Sustained realtime silence needed before an early cut. */
    var tailConfirmMs: Long by settings::tailConfirmMs
    /** Set by poll(): realtime tail watch armed (cheap per-chunk check reads this). */
    @Volatile var tailWatch: Boolean = config.silenceSkipEnabled
    /** Tempo (BPM) detection is opt-in; each preloaded track decodes one head window for onset analysis. */
    var bpmDetectionEnabled: Boolean by settings::bpmDetectionEnabled
    var bpmScanMs: Long by settings::bpmScanMs
    var keyDetectionEnabled: Boolean by settings::keyDetectionEnabled
    var keyScanMs: Long by settings::keyScanMs
    var beatmatchEnabled: Boolean by settings::beatmatchEnabled
    var maxTempoChange: Float by settings::maxTempoChange
    var beatmatchMinConfidence: Float by settings::beatmatchMinConfidence

    // ---- Protocol State ----
    private val protocol = ProtocolController(object : MixerCore {
        override val guildId: Long get() = this@GuildMixer.guildId
        override val lock: Any get() = this@GuildMixer.lock
        override val playerMutationLock: Any get() = this@GuildMixer.playerMutationLock
        override val playerManager: AudioPlayerManager get() = this@GuildMixer.playerManager
        override val mainPlayer: IPlayer? get() = main
        override val playingTrack: AudioTrack? get() = mainTrack()
        override val crossfadeMs: Long get() = this@GuildMixer.crossfadeMs
        override val prefetchLeadMs: Long get() = if (bpmDetectionEnabled && beatmatchEnabled) {
            (bpmScanMs * 2 + 7000L).coerceAtMost(120_000L)
        } else 7000L
        override val isXfadeActive: Boolean get() = xfadeActive
        override val isOverlayActive: Boolean get() = overlays.overlayActive

        override fun safePosition(track: AudioTrack): Long = this@GuildMixer.safePosition(track)

        override fun clearNextLocked() {
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
            deck.mainGain.rampTo(1f, msToSamples(duckFadeMs))
        }

        override fun syncPlayingStateOnResetLocked(playing: AudioTrack?) {
            if (playing != null) {
                syncChainPos(this@GuildMixer.safePosition(playing))
                if (normalizationEnabled) {
                    knownNormGainLocked(playing)?.let { mainNorm.rampTo(it, msToSamples(NORM_RAMP_MS)) }
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

    /**
     * Normalization gain for the currently playing main track. Only the elected
     * feeder advances it in [advance]; a loudness result that arrives mid-track
     * ramps over [NORM_RAMP_MS] instead of stepping the level.
     */
    private val mainNorm = GainRamp()
    /** Normalization gain for the armed sub (crossfade) track; 1 while no overlap is armed. */
    private val subNorm = GainRamp()
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
    private var preservedStartTrack: AudioTrack? = null
    data class TransitionStatus(val kind: String, val reason: String?)
    @Volatile var transitionStatus = TransitionStatus("none", null)
        private set
    private var readyNextTrack: AudioTrack? = null
    private var attachedPlayers = mutableSetOf<AudioPlayer>()

    private val analysis = AnalysisCoordinator(object : AnalysisHost {
        override val guildId: Long get() = this@GuildMixer.guildId
        override val lock: Any get() = this@GuildMixer.lock
        override val normGainBaseline: Float get() = this@GuildMixer.normGainBaseline
        override val playingTrack: AudioTrack? get() = mainTrack()

        override fun queuedTracksLocked(): List<AudioTrack> = listOfNotNull(pendingNextTrack, readyNextTrack)

        override fun promoteIfPendingLocked(expectedId: String?) =
            this@GuildMixer.promoteIfPendingLocked(expectedId)

        override fun applyAnalyzedNormGain(identifier: String) =
            this@GuildMixer.applyAnalyzedNormGain(identifier)
    }, settings, playerManager, analysisExecutor)
    private val overlays: OverlayController = OverlayController(object : OverlayHost {
        override val guildId: Long get() = this@GuildMixer.guildId
        override val lock: Any get() = this@GuildMixer.lock
        override val transitionBusyLocked: Boolean get() = xfadeActive || overlapPreparation != null
        override val subListener: AudioEventListener get() = this@GuildMixer.subListener

        override fun resetSubNormLocked() = subNorm.setNow(1f)
        override fun isFeeder(feederId: Long): Boolean = this@GuildMixer.isFeeder(feederId)
        override fun notifyMixerEvent(type: String, body: JsonObjectBuilder.() -> Unit) =
            this@GuildMixer.notifyMixerEvent(type, body)
    }, settings, deck, playerManager)
    /** Voice thread reads this without taking [lock]; races only shift a fade by a chunk. */
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
    val currentXfadeSample: Long get() = xfadeRecipeSampleAt(chainPos.get())

    /** Chain position (samples) the voice thread has mixed up to. */
    val chainSamplePos: Long get() = chainPos.get()

    /**
     * Maps a chain position to the recipe timeline. When the overlap started late
     * (slow arm/prefill), the recipe is compressed into the remaining window so
     * the outgoing gain still reaches zero at the fixed handoff instead of
     * cutting abruptly while the outgoing track is still audible.
     */
    fun xfadeRecipeSampleAt(chainSample: Long): Long {
        val start = xfadeStartSample
        if (start < 0L) return 0L
        val elapsed = (chainSample - start).coerceAtLeast(0L)
        val recipe = activeRecipe ?: return elapsed
        val window = xfadeEndSample - start
        val total = recipe.totalSamples
        if (window <= 0L || total <= window) return elapsed
        return (elapsed.toDouble() * total / window).toLong()
    }

    private var xfadeHandoffMs: Long = -1L
    /** Incoming track whose analysis the poll loop is waiting on, and the chain position the wait began at. */
    private var featureWaitTrack: AudioTrack? = null
    private var featureWaitStartSample: Long = 0L
    /** Main-track instance the armed overlap belongs to; a handoff must never fire after that track was replaced. */
    private var xfadeMainTrack: AudioTrack? = null
    /** Position within [SubDeck.track] that has reached the audible output. */
    private val subOutputSamples = AtomicLong(0L)
    private val subSourceMilliSamples = AtomicLong(0L)
    /** Per-chunk incoming source position, so a handoff can bridge already-mixed output. */
    private val handoffSourceMap = HandoffSourceMap()
    @Volatile private var xfadeBeatMatched: Boolean = false
    @Volatile private var xfadeFallbackReason: String? = null
    private var subStartPositionMs: Long = 0L
    private var masking: Boolean = false
    private var maskingTrack: AudioTrack? = null
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

    val siphon: SiphonQueue get() = deck.siphon

    private data class StartSnap(val xfade: Boolean, val leadMs: Long, val fadeInMs: Long, val fadeTarget: Float)

    val mainListener = object : AudioEventAdapter() {
        override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
            // The idle carrier is not playback: no state machine, analysis or
            // sequence bookkeeping. Anything else starting means a real track
            // took over the player, so the carrier reference is stale.
            if (SilenceAudioTrack.isCarrier(track)) return
            synchronized(lock) { idleCarrierIdleSinceMs = 0L }
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
                    val targetGain = if (overlays.overlayActive || overlays.hasParallelLocked) duckLevel.coerceIn(0f, 1f) else 1f
                    StartSnap(
                        xfadeActive,
                        if (silenceSkipEnabled && !preserveStart) leadForLocked(track) else -1L,
                        if (preserveStart) 0L else fadeInMs,
                        targetGain,
                    )
                }
            }
            if (snap == null) {
                // Masked handoff start: doHandoff already carried the sub's
                // normalization gain over for this track; do not reset it.
                return
            }
            // Track-level normalization toward targetLufs. A track whose loudness
            // is not known yet keeps the previous track's level rather than
            // jumping to unity; the scan below ramps it once an estimate lands.
            if (normalizationEnabled) {
                synchronized(lock) { knownNormGainLocked(track) }?.let { mainNorm.setNow(it) }
            } else {
                mainNorm.setNow(1f)
            }
            // Unmeasured track started directly: scan it in the background
            // outside the lock (sub plays block on source I/O). A busy analysis
            // slot is covered by the playing-track backfill in analyzeBlocking.
            val needsBaselineAnalysis = if (normalizationEnabled) {
                synchronized(lock) {
                    val id = idOf(track)
                    normGainBaseline == 0f &&
                        !analysis.activeLocked &&
                        (id == null || !AnalysisCaches.norm.contains(id))
                }
            } else false
            // Unify main-track loudness baseline and head/tail preanalysis into a single
            // open when BPM, key, or silence detection is also enabled.
            val scheduledPreanalysis = if (bpmDetectionEnabled || keyDetectionEnabled || silenceSkipEnabled) {
                val clone = runCatching { track.makeClone() }.getOrNull()
                if (clone != null) {
                    requestPreanalysis(clone, includeNormalization = needsBaselineAnalysis)
                } else false
            } else false
            if (needsBaselineAnalysis && !scheduledPreanalysis) {
                scheduleBaselineAnalysis(track)
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
                deck.mainGain.setNow(0f)
                deck.mainGain.rampTo(snap.fadeTarget, msToSamples(snap.fadeInMs))
            }
            // After the lead-skip seek above: this is where the chain resumes.
            syncChainPos(track.position)
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            if (SilenceAudioTrack.isCarrier(track)) return
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            if (endReason != AudioTrackEndReason.FINISHED && endReason != AudioTrackEndReason.LOAD_FAILED) {
                if (endReason == AudioTrackEndReason.STOPPED || endReason == AudioTrackEndReason.CLEANUP) {
                    synchronized(lock) {
                        if (!xfadeActive && mainTrack() == null) {
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
            if (SilenceAudioTrack.isCarrier(track)) return
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' stuck on guild {} (threshold {}ms); mixer failover", title, guildId, thresholdMs)
            scheduleMainRecovery(player, track)
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            if (SilenceAudioTrack.isCarrier(track)) return
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

    private val subListener: AudioEventListener = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            val overlay = synchronized(lock) { overlays.overlayActive }
            if (overlay) {
                overlays.finishOverlay()
                return
            }
            val abort = synchronized(lock) {
                if (!xfadeActive || deck.track !== track) false
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
                    overlays.overlayActive -> 1
                    xfadeActive -> 2
                    else -> 0
                }
            }
            when (action) {
                1 -> overlays.finishOverlay()
                2 -> abortXfade("sub track stuck/failed")
            }
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

    /**
     * The track the bot is playing on the main player. Null while the player is
     * idle or only plays the internal [SilenceAudioTrack] carrier: the carrier
     * must never look like playback to transitions, JIT, analysis or recovery.
     */
    internal fun mainTrack(): AudioTrack? {
        val playing = main?.audioPlayer?.playingTrack
        return if (SilenceAudioTrack.isCarrier(playing)) null else playing
    }

    /** True while the idle-announcement carrier is what the main player plays. */
    fun isIdleCarrierActive(): Boolean = SilenceAudioTrack.isCarrier(main?.audioPlayer?.playingTrack)

    /** Wall-clock time the carrier last became unneeded; 0 while needed. Guarded by [lock]. */
    private var idleCarrierIdleSinceMs: Long = 0L

    private enum class CarrierAction { NONE, START, STOP }

    /**
     * Scheduler tick. Plays the silent carrier on an idle main player while an
     * announcement is queued, resolving or audible, so [MixerFilter] gets chunks to
     * mix into; stops it once nothing needs it for [IDLE_CARRIER_GRACE_MS]. A track
     * the bot starts replaces the carrier through Lavaplayer, so yielding needs no
     * work here.
     */
    fun maintainIdleCarrier() {
        val action = synchronized(lock) {
            val player = main?.audioPlayer ?: return
            val playing = player.playingTrack
            val needed = overlays.overlayActive || overlays.hasParallelLocked || overlays.queueHasWorkLocked
            when {
                needed -> {
                    idleCarrierIdleSinceMs = 0L
                    if (playing == null && !player.isPaused) CarrierAction.START else CarrierAction.NONE
                }
                SilenceAudioTrack.isCarrier(playing) -> {
                    val now = System.currentTimeMillis()
                    if (idleCarrierIdleSinceMs == 0L) idleCarrierIdleSinceMs = now
                    if (now - idleCarrierIdleSinceMs >= IDLE_CARRIER_GRACE_MS) CarrierAction.STOP else CarrierAction.NONE
                }
                else -> {
                    idleCarrierIdleSinceMs = 0L
                    CarrierAction.NONE
                }
            }
        }
        when (action) {
            CarrierAction.START -> startIdleCarrier()
            CarrierAction.STOP -> stopIdleCarrier("no announcement left")
            CarrierAction.NONE -> Unit
        }
    }

    private fun startIdleCarrier() {
        synchronized(playerMutationLock) {
            val player = synchronized(lock) { main?.audioPlayer } ?: return
            // noInterrupt: a track the bot started since the check above wins.
            val started = try {
                player.startTrack(SilenceAudioTrack(), true)
            } catch (e: Exception) {
                log.warn("idle announcement carrier failed to start on guild {}", guildId, e)
                false
            }
            if (started) log.info("idle announcement carrier started on guild {}", guildId)
        }
    }

    /**
     * Ends the carrier with `stopTrack()` so the client sees a `stopped` end.
     * Lavaplayer has no compare-and-stop, so the identity check below leaves a
     * microsecond window in which a bot track started right now would be stopped.
     */
    private fun stopIdleCarrier(reason: String) {
        synchronized(playerMutationLock) {
            val player = synchronized(lock) { main?.audioPlayer } ?: return
            if (!SilenceAudioTrack.isCarrier(player.playingTrack)) return
            try {
                player.stopTrack()
                log.info("idle announcement carrier stopped on guild {} ({})", guildId, reason)
            } catch (e: Exception) {
                log.warn("idle announcement carrier stop failed on guild {}", guildId, e)
            }
        }
    }

    fun newFilter(downstream: com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter? = null): MixerFilter =
        MixerFilter(this, downstream)

    // ---- voice-thread entry points ----

    /** Advances ramps by [samples] and returns the gains to mix with. */
    fun advance(samples: Int, feederId: Long): Gains {
        if (!claimFeeder(feederId)) {
            // Rival chain: snapshot only — mutating ramps/chainPos here would
            // double every fade and run the chain counter at 2x.
            return Gains(deck.mainGain.current * mainNorm.current, deck.subGain.current * subNorm.current)
        }
        val endOfChunk = chainPos.addAndGet(samples.toLong())
        // Lock-free on purpose: this runs on the voice thread for every ~20ms
        // chunk, and blocking here stalls main-track output until Lavaplayer
        // reports the (healthy) track as stuck. GainRamp fields are volatile;
        // a race with the control plane only shifts a fade by one chunk.
        if (xfadeActive) {
            val recipe = activeRecipe
            if (recipe != null) {
                val currentSample = xfadeRecipeSampleAt(endOfChunk)
                val m = recipe.gainAt(TrackTarget.OUTGOING, currentSample, 1.0f)
                val s = recipe.gainAt(TrackTarget.INCOMING, currentSample, 0.0f)
                deck.mainGain.setNow(m)
                deck.subGain.setNow(s)
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
                deck.mainGain.setNow(cos(u * PI.toFloat() / 2f))
                deck.subGain.setNow(sin(u * PI.toFloat() / 2f))
            }
        } else {
            deck.mainGain.advance(samples)
            deck.subGain.advance(samples)
        }
        mainNorm.advance(samples)
        subNorm.advance(samples)
        return Gains(deck.mainGain.current * mainNorm.current, deck.subGain.current * subNorm.current)
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
        if (read.outputSamples > 0) handoffSourceMap.record(chunkStart + samples, read.sourcePositionMilliSamples)
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

    fun advanceParallelOverlays(samples: Int, feederId: Long): List<OverlaySlot> =
        overlays.advanceParallel(samples, feederId)

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
        if (tailWatch && silenceSkipEnabled && !SilenceAudioTrack.isCarrier(main?.audioPlayer?.playingTrack) && !protocol.preserveTransition) SilenceAnalyzer.thresholdAmp(silenceThresholdDb) else null

    fun noteChunk(peak: Float, samples: Int, thresholdAmp: Float) =
        tailCutter.note(peak, samples, thresholdAmp, chainPos.get())

    /** Test seam: bypass ramps and pin gains. */
    internal fun gainsForTest(main: Float, sub: Float) {
        deck.mainGain.setNow(main)
        deck.subGain.setNow(sub)
    }

    /** Test seam: install a stub secondary player before the deck creates its own. */
    internal fun installSubPlayerForTest(sub: AudioPlayer) = synchronized(lock) { deck.player = sub }

    /** Test seam: pin the normalization gains without a decoder. */
    internal fun normalizeForTest(mainGain: Float, subGain: Float = 1f) {
        mainNorm.setNow(mainGain)
        subNorm.setNow(subGain)
    }

    /** Test seam: read the sub normalization gain without a decoder. */
    internal fun subNormGainForTest(): Float = subNorm.current

    /** Test seam: deliver a loudness result as the analysis thread would. */
    internal fun loudnessForTest(identifier: String, lufs: Float, provisional: Boolean = false) {
        if (provisional) {
            analysis.noteProvisionalLoudness(identifier, lufs)
        } else {
            AnalysisCaches.norm[identifier] = lufs
            applyAnalyzedNormGain(identifier)
        }
    }

    /** Test seam: arm an overlap's incoming normalization exactly as the crossfade path does. */
    internal fun armSubNormForTest(next: AudioTrack) = synchronized(lock) {
        subNorm.setNow(incomingNormGainLocked(next))
    }

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
    internal fun currentSubEffectiveSpeedForTest(): Double = deck.factory?.effectiveSpeed ?: 1.0

    internal fun filterRunsForTest(): Long = filterRunCount.get()

    internal fun filterMissingForTest(): Boolean = filterMissing

    /** Advances the heartbeat baseline as if a fresh main track just started. */
    internal fun resetFilterHeartbeatForTest(position: Long = 0L) {
        synchronized(lock) { resetFilterHeartbeatLocked(position) }
    }

    internal fun trimForTest(identifier: String): TrimPoints? =
        synchronized(lock) { AnalysisCaches.trim[identifier] }

    /** Test seam: count currently active parallel overlay slots. */
    internal fun parallelOverlayCountForTest(): Int = overlays.parallelCountForTest()
    internal fun installParallelOverlayForTest(slot: OverlaySlot) = overlays.installParallelForTest(slot)
    internal fun overlayForTest(active: Boolean, channel: AnnounceChannel = AnnounceChannel.BOTH) =
        overlays.overlayForTest(active, channel)

    /** Test seam: simulate [count] active parallel slots without players (for the limit check). */
    internal fun armParallelForTest(count: Int) = overlays.armParallelForTest(count)

    // ---- scheduler entry points ----

    /** Keeps the secondary decoder flowing; its PCM is captured by the siphon filter. */
    fun drain() {
        val sub = synchronized(lock) { deck.player?.takeIf { it.playingTrack != null } }
        // Providing frees the decoder's frame buffer and lets it produce more PCM.
        // Keep prewarmed audio bounded while the main decoder has not reached the fade.
        if (sub != null && (overlays.overlayActive || siphon.pendingSamples < SAMPLE_RATE)) {
            try {
                sub.provide(deck.drainFrame)
            } catch (e: Exception) {
                log.warn("sub drain failed on guild {}", guildId, e)
            }
        }
        // Drive every active parallel overlay decoder too; each has its own
        // player and siphon, and none of them are drained anywhere else.
        val parallels = synchronized(lock) {
            overlays.drainableParallelsLocked()
        }
        for (slot in parallels) {
            try {
                slot.player.provide(deck.drainFrame)
            } catch (e: Exception) {
                log.warn("parallel overlay drain failed on guild {}", guildId, e)
            }
        }
    }

    private data class TransitionFence(val outgoing: AudioTrack?, val incoming: AudioTrack?, val epoch: Long, val seq: Long, val slotRevision: Long)

    private fun transitionFenceLocked() = TransitionFence(mainTrack(),
        readyNextTrack ?: pendingNextTrack, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), slotRevision)

    private fun matchesFenceLocked(fence: TransitionFence): Boolean =
        mainTrack() === fence.outgoing && (readyNextTrack ?: pendingNextTrack) === fence.incoming &&
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
        val track = mainTrack() ?: return
        val pos = safePosition(track)
        val chain = chainPos.get() * 1000L / SAMPLE_RATE
        val handoff: Long
        val subPos: Long
        val subOutMs: Long
        synchronized(lock) {
            handoff = xfadeHandoffMs
            subPos = deck.track?.let { safePosition(it) } ?: -1L
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
        val track = mainTrack() ?: run {
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
                val incomingTrack = readyNextTrack ?: pendingNextTrack
                val plan = planCrossfadeLocked(track, incomingTrack, bridgeSafeEndLocked(contentEnd, duration, incomingTrack))
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
                    if (!analysis.incomingFeaturesReadyLocked(incoming)) {
                        // A late arm left the incoming track's beatmatching scan
                        // still running when the fade window opened. Wait for the
                        // scan to land instead of firing a plain fade with
                        // missing_features. The wait is bounded: if the scan never
                        // lands (failed, aborted, never scheduled) start the overlap
                        // anyway and let the planner degrade; at file end advance.
                        if (pos >= duration - 500) return PollAction.AdvanceNext(transitionFenceLocked())
                        if (featureWaitTrack !== incoming) {
                            featureWaitTrack = incoming
                            featureWaitStartSample = chainPos.get()
                        }
                        if (chainPos.get() - featureWaitStartSample < FEATURE_WAIT_MS * SAMPLE_RATE / 1000) return PollAction.None
                    }
                    val fence = transitionFenceLocked()
                    val analysisLog = transitionAnalysisLog(fence.outgoing?.let { idOf(it) }, fence.incoming?.let { idOf(it) }) +
                        " limits={minConfidence=$beatmatchMinConfidence maxTempoChange=$maxTempoChange}"
                    return PollAction.StartOverlap(plan, fence, analysisLog)
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
        if (xfadeBeatMatched && deck.factory?.nativeFailed == true) {
            xfadeBeatMatched = false
            xfadeFallbackReason = "timescale_native_failed"
            transitionStatus = TransitionStatus("crossfade", "timescale_native_failed")
        }
    }

    /**
     * Ends the crossfade early enough that the outgoing decoder is still
     * running at the handoff: only output it mixes after the handoff point
     * can bridge the clone's start (see [HandoffBridge]). A handoff at the
     * physical file end leaves nothing buffered, and a remote incoming
     * source is then heard as a gap while it opens. Local files open in
     * microseconds and keep the full window.
     */
    private fun bridgeSafeEndLocked(contentEnd: Long, duration: Long, incoming: AudioTrack?): Long {
        val headroom = handoffBridgeMs
        if (headroom <= 0L || incoming == null || isLocalSource(incoming)) return contentEnd
        val latest = duration - headroom
        if (latest <= 0L) return contentEnd
        return minOf(contentEnd, latest)
    }

    private fun isLocalSource(track: AudioTrack): Boolean =
        try { track.sourceManager?.sourceName == "local" } catch (_: Exception) { false }

    private fun planCrossfadeLocked(
        outgoingTrack: AudioTrack,
        incomingTrack: AudioTrack?,
        contentEndMs: Long,
    ): CrossfadePlan? {
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
        val inLead = maxOf(
            if (silenceSkipEnabled) leadForLocked(incomingTrack) else 0L,
            inFeatures?.leadingSilenceMs ?: 0L,
            protocol.armedStartPositionMs,
        )
        val inDuration = try { incomingTrack.duration } catch (e: Exception) { -1L }
        val recipe = AdaptiveTransitionPlanner.plan(
            outgoingGridTail = outGrid,
            incomingGridHead = inGrid,
            outgoingFeaturesTail = outId?.let { AnalysisCaches.featuresTail[it] },
            incomingFeaturesHead = inFeatures,
            requestedCrossfadeMs = crossfadeMs,
            contentEndMs = contentEndMs,
            incomingLeadMs = inLead,
            incomingDurationMs = inDuration,
            maxTempoChange = maxTempoChange,
            minConfidence = beatmatchMinConfidence,
            preserveContext = protocol.preserveTransition,
            guildId = guildId,
            memory = TransitionMemory.global,
            trackPairSeed = 31 * (outId?.hashCode() ?: 0) + (inId?.hashCode() ?: 0),
        )
        transitionStatus = TransitionStatus(AdaptiveTransitionPlanner.legacyKind(recipe.strategy), recipe.reason)
        return AdaptiveTransitionPlanner.toCrossfadePlan(recipe, contentEndMs)?.copy(
            transitionKind = transitionStatus.kind, reason = transitionStatus.reason, recipe = recipe,
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

    fun isOverlayActive(): Boolean = overlays.overlayActive
    fun currentOverlayChannel(): AnnounceChannel = overlays.sequentialChannel
    fun isXfadeActive(): Boolean = synchronized(lock) { xfadeActive }
    fun isSubBusy(): Boolean = synchronized(lock) { overlays.overlayActive || xfadeActive || overlapPreparation != null || analysis.activeLocked || overlays.hasParallelLocked }

    // ---- announcements and overlays ----

    /** Queues an announcement; returns its id and 1-based position among queued announcements. */
    fun enqueueAnnouncement(
        identifier: String?,
        encodedTrack: String?,
        duckLevel: Float,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
        gain: Float = 1f,
    ): Pair<Long, Int> = overlays.enqueueAnnouncement(identifier, encodedTrack, duckLevel, mode, channel, gain)

    fun takeNextAnnouncement(): AnnouncementItem? = overlays.takeNextAnnouncement()

    /** Puts an item back at the head after a busy race or transient failure. */
    fun requeueAnnouncement(item: AnnouncementItem, cause: Throwable?) = overlays.requeueAnnouncement(item, cause)

    /** Drops an item whose track could not be resolved/started. */
    fun onAnnouncementFailed(item: AnnouncementItem, cause: Throwable?) = overlays.onAnnouncementFailed(item, cause)

    fun onAnnouncementStarted(item: AnnouncementItem) = overlays.onAnnouncementStarted(item)

    fun announcementQueueSize(): Int = overlays.announcementQueueSize()

    /** Removes all queued (not active) announcements; returns how many were dropped. */
    fun clearAnnouncements(): Int = overlays.clearAnnouncements()

    fun startOverlay(
        track: AudioTrack,
        duck: Float,
        announcementId: Long? = null,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
        gain: Float = 1f,
    ): AudioTrack = overlays.startOverlay(track, duck, announcementId, mode, channel, gain)

    fun cancelOverlay(): Boolean = overlays.cancelOverlay()

    /**
     * Normalization gain the main track is playing at or ramping toward (1 while
     * disabled). An unmeasured track reports the level it inherited.
     */
    fun currentTrackGain(): Float = mainNorm.target

    /** Analyzed gain of the queued next track; null while analysis is pending. */
    fun nextTrackGain(): Float? = synchronized(lock) {
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        knownNormGainLocked(next)
    }

    /** Identifier of the preheated next track (pending or ready), so clients can verify which track the server will start. */
    fun nextTrackIdentifier(): String? = synchronized(lock) { (readyNextTrack ?: pendingNextTrack)?.let { idOf(it) } }

    fun nextTrackTitle(): String? = synchronized(lock) { (readyNextTrack ?: pendingNextTrack)?.let { titleOf(it) } }

    /** Identifier of the armed overlay/overlap sub track; non-null while the secondary decoder is in use. */
    fun subTrackIdentifier(): String? = synchronized(lock) { deck.track?.let { idOf(it) } }

    fun analysisState(): String = synchronized(lock) {
        when {
            analysis.activeLocked -> "analyzing"
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
        val pos = mainTrack()?.let { safePosition(it) } ?: 0L
        (xfadeHandoffMs - pos).coerceAtLeast(0)
    }

    fun destroy() {
        val (sub, parallels) = synchronized(lock) {
            pendingNextTrack = null
            readyNextTrack = null
            overlapPreparation = null
            protocol.clearArmedLocked()
            deck.track = null
            xfadeActive = false
            activeRecipe = null
            analysis.resetLocked()
            deck.factory?.resetSpeed()
            val p = overlays.destroyLocked()
            deck.player.also { deck.player = null } to p
        }
        try {
            sub?.destroy()
        } catch (e: Exception) {
            log.warn("sub destroy failed on guild {}", guildId, e)
        }
        analysis.destroy()
        TransitionMemory.global.clear(guildId)
        for (slot in parallels) {
            try {
                slot.player.destroy()
            } catch (e: Exception) {
                log.warn("parallel overlay destroy failed on guild {}", guildId, e)
            }
        }
        stopIdleCarrier("mixer destroyed")
        protocol.shutdown()
    }

    // ---- internals (all called under lock) ----

    private fun prepareOverlap(plan: CrossfadePlan, fence: TransitionFence, analysisLog: String) {
        synchronized(lock) {
            if (overlapPreparation != null || overlays.overlayActive || xfadeActive || !matchesFenceLocked(fence)) return
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
            val s = deck.ensureLocked(subListener)
            siphon.clear()
            if (plan.beatMatched) {
                deck.factory?.configureTempo(plan.rateRatio.toDouble(), plan.overlapMs * SAMPLE_RATE / 1000, plan.recoveryMs * SAMPLE_RATE / 1000)
            } else {
                deck.factory?.resetSpeed()
            }
            s
        }
        try {
            sub.playTrack(next)
        } catch (e: Exception) {
            log.warn("overlap pre-start play failed on guild {}", guildId, e)
        }
        val ready = deck.prefill(sub) {
            synchronized(lock) { crossfadeEnabled && !protocol.preserveTransition && matchesFenceLocked(expected) && !overlays.overlayActive && !xfadeActive }
        }
        val started = synchronized(lock) {
            val startSample = maxOf(plan.startMs * SAMPLE_RATE / 1000, chainPos.get())
            if (!ready || !crossfadeEnabled || protocol.preserveTransition || overlays.overlayActive || xfadeActive ||
                startSample >= plan.handoffMs * SAMPLE_RATE / 1000 || !matchesFenceLocked(expected) ||
                protocol.gate.lastKnownEpoch != epochAtStart || protocol.advanceSeq.get() != seqAtStart ||
                slotRevision != revisionAtStart || protocol.playbackState != PlaybackState.ARMED) {
                if (!xfadeActive) deck.factory?.resetSpeed()
                null
            } else {
                if (readyNextTrack === queued) readyNextTrack = null
                if (pendingNextTrack === queued) pendingNextTrack = null
                val nativeFailed = deck.factory?.nativeFailed == true
                if (plan.beatMatched && nativeFailed) {
                    deck.factory?.resetSpeed()
                }
                xfadeBeatMatched = plan.beatMatched && !nativeFailed
                xfadeFallbackReason = if (plan.beatMatched && nativeFailed) "timescale_native_failed" else plan.fallbackReason

                deck.track = next
                xfadeTransitionKind = if (nativeFailed) "crossfade" else plan.transitionKind
                xfadeDecisionReason = if (nativeFailed) "timescale_native_failed" else plan.reason
                xfadeHandoffMs = plan.handoffMs
                xfadeMainTrack = mainTrack()
                subOutputSamples.set(0L)
                subSourceMilliSamples.set(0L)
                handoffSourceMap.reset()
                subStartPositionMs = incomingStartPosition
                deck.startMs = System.currentTimeMillis()
                xfadeTotal = msToSamples(plan.durationMs).coerceAtLeast(1)
                xfadeLeft = xfadeTotal
                xfadeStartSample = startSample
                xfadeEndSample = plan.handoffMs * SAMPLE_RATE / 1000
                xfadeFadeEndSample = maxOf(startSample + 1, (plan.startMs + plan.overlapMs) * SAMPLE_RATE / 1000)
                // Sub-track normalization gain: applied on top of the sin curve.
                activeRecipe = plan.recipe
                plan.recipe?.let { TransitionMemory.global.record(guildId, it.strategy) }
                subNorm.setNow(incomingNormGainLocked(next))
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
                StartedOverlap(++deck.gen, startSample, xfadeBeatMatched, xfadeFallbackReason)
            }
        }
        if (started == null) {
            if (sub.playingTrack === next) {
                runCatching { sub.stopTrack() }.onFailure { log.warn("superseded overlap stop failed on guild {}", guildId, it) }
            }
            if (synchronized(lock) { !xfadeActive && !overlays.overlayActive }) siphon.clear()
            return
        }
        // A control command (override/invalidate/reset/arm) that aborted the fade
        // while playTrack was in flight bumps deck.gen and stops an empty sub —
        // the playTrack above then decodes a zombie into a torn-down slot.
        // Detect and unwind it: stop the decoder and only restore if not superseded by control plane.
        val stillCurrent = synchronized(lock) { deck.gen == started.generation && xfadeActive && deck.track === next }
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
                if (!xfadeActive || deck.track === next) {
                    deck.factory?.resetSpeed()
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
            if (synchronized(lock) { !xfadeActive && !overlays.overlayActive }) siphon.clear()
            return
        }
        val recipeStrategy = activeRecipe?.strategy?.name?.lowercase() ?: "crossfade"
        val beatmatchLog = if (started.beatMatched) {
            "strategy=$recipeStrategy targetBpm=${plan.targetBpm} rateRatio=${plan.rateRatio} fadeBeats=${plan.fadeBeats}"
        } else "strategy=$recipeStrategy rateRatio=1.0"
        log.info(
            "crossfade guild={} from='{}' to='{}' beatMatched={} reason={} durationMs={} lateMs={} {} {}",
            guildId, expected.outgoing?.let { titleOf(it) }?.replace('\n', ' ')?.replace('\r', ' '),
            titleOf(next).replace('\n', ' ').replace('\r', ' '), started.beatMatched,
            started.fallbackReason ?: plan.reason ?: "none",
            (plan.handoffMs * SAMPLE_RATE / 1000 - started.startSample) * 1000 / SAMPLE_RATE,
            (started.startSample - plan.startMs * SAMPLE_RATE / 1000) * 1000 / SAMPLE_RATE,
            beatmatchLog, analysisLog,
        )
        val mainPosMs = mainTrack()?.let { safePosition(it) } ?: 0L
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
        val subStartPositionMs: Long,
        val sourceMap: HandoffSourceMap.Snapshot,
    )

    private fun doHandoff() {
        val handoffAllowed = synchronized(lock) {
            val armed = xfadeMainTrack ?: return@synchronized true
            val playing = mainTrack()
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
            // Before anything below changes what the feeder mixes: output
            // after this point is never bridged.
            val sourceMap = handoffSourceMap.freeze()
            refreshBeatmatchStatusLocked()
            val wasBeatMatched = xfadeBeatMatched
            val fallbackReason = xfadeFallbackReason
            xfadeBeatMatched = false
            xfadeFallbackReason = null
            val sub = deck.track
            val armedEnc = protocol.armedEncodedTrack
            protocol.armedTrack = null
            protocol.armedEncodedTrack = null
            protocol.armedStartPositionMs = 0L
            protocol.armedOutgoingEndPositionMs = 0L
            protocol.armedOutgoingTrack = null
            xfadeActive = false
            activeRecipe = null
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
                        subStartPositionMs = subStartPositionMs,
                        sourceMap = sourceMap,
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
        val sub = synchronized(lock) { deck.player }
        synchronized(playerMutationLock) {
            // Re-check under lock inside playerMutationLock: if an override/invalidate/reset
            // arrived while seeking or waiting for playerMutationLock, abort before touching
            // deck.mainGain or replacing the new main track.
            val committedSeq = synchronized(lock) {
                if (protocol.gate.lastKnownEpoch != plan.epochAtStart || protocol.advanceSeq.get() != plan.seqAtStart) {
                    if (deck.track === handoff.source) deck.track = null
                    resetGainsLocked()
                    null
                } else {
                    if (deck.track === handoff.source) deck.track = null
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
                deck.factory?.resetSpeed()
                siphon.clear()
                log.warn("crossfade handoff superseded on guild {}; dropping '{}'", guildId, titleOf(handoff.source))
                return
            }
            try {
                sub?.stopTrack()
            } catch (e: Exception) {
                log.warn("sub stop failed on guild {}", guildId, e)
            }
            deck.factory?.resetSpeed()
            siphon.clear()
            deck.subGain.setNow(0f)
            deck.mainGain.setNow(0f)
            deck.mainGain.rampTo(1f, msToSamples(maskMs).coerceAtLeast(1))
            // Handoff carries the sub track's normalization gain over to the
            // main slot so the incoming track keeps its level through the mask.
            mainNorm.copyFrom(subNorm)
            subNorm.setNow(1f)
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
                val bridge = playHandoffClone(mainPlayer, handoff)
                log.debug(
                    "crossfade handoff on guild {} at {}ms (sub decoder was at {}ms, " +
                        "{}ms of sub audio heard, {} frames dropped; {})",
                    guildId, bridge.positionMs.takeIf { it >= 0 } ?: handoff.outputPosition,
                    handoff.subReportedMs, handoff.subOutMs, handoff.droppedFrames, bridge.describe(),
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

    private class BridgeResult {
        @Volatile var frames: Int = 0
        @Volatile var bridgeMs: Long = 0L
        @Volatile var positionMs: Long = -1L
        @Volatile var skipped: String? = null

        fun describe(): String =
            skipped?.let { "no bridge: $it" } ?: "bridged ${bridgeMs}ms ($frames frames), clone starts at ${positionMs}ms"
    }

    /**
     * Starts the handoff clone behind the outgoing track's already-mixed
     * frames (see [HandoffBridge]). The clone starts where those frames end,
     * so its open/seek latency is covered instead of heard as a gap. Falls
     * back to a plain start at [Handoff.outputPosition] with the mask ramp.
     */
    private fun playHandoffClone(mainPlayer: IPlayer, handoff: Handoff): BridgeResult {
        val result = BridgeResult()
        val outgoingBuffer = HandoffBridge.bufferOf(mainPlayer.audioPlayer.playingTrack)
        val sourceMap = handoff.sourceMap
        result.skipped = when {
            !handoff.clone.isSeekable -> "incoming track is not seekable"
            outgoingBuffer == null -> "outgoing track has no local frame buffer"
            sourceMap.chainEnds.isEmpty() -> "no mixed incoming audio recorded"
            !HandoffBridge.install(playerManager.configuration) -> "frame buffer factory unavailable"
            else -> null
        }
        if (result.skipped != null || outgoingBuffer == null) {
            mainPlayer.play(handoff.clone)
            return result
        }
        val startedAt = System.nanoTime()
        // Cleared by the builder; stays set when Lavaplayer did not create the
        // clone's buffer through the wrapped factory on this thread.
        result.skipped = "clone buffer was not created through the bridge factory"
        val builder = object : HandoffBridge.PrefixBuilder {
            override fun build(format: AudioDataFormat): List<AudioFrame>? {
                val frameMs = format.frameDuration().toLong()
                val lastChainEndMs = sourceMap.lastChainEnd * 1000L / SAMPLE_RATE
                val frames = HandoffBridge.drain(outgoingBuffer, lastChainEndMs - frameMs, format)
                if (frames.isEmpty()) {
                    result.skipped = "outgoing buffer held no mixed incoming audio"
                    return null
                }
                val endMs = frames.last().timecode + frameMs
                val source = sourceMap.sourceAt(endMs * SAMPLE_RATE / 1000L)
                if (source == null) {
                    result.skipped = "buffered audio ends outside the recorded source map"
                    return null
                }
                val duration = handoff.clone.duration
                val positionMs = (handoff.subStartPositionMs + source / 1000L * 1000L / SAMPLE_RATE).let {
                    if (duration > 0) it.coerceIn(0L, duration) else it.coerceAtLeast(0L)
                }
                // Runs before the clone's executor exists: this becomes its initial seek.
                handoff.clone.position = positionMs
                // The seam is continuous; the mask ramp would only dip it.
                deck.mainGain.setNow(1f)
                result.skipped = null
                result.frames = frames.size
                result.bridgeMs = frames.size * frameMs
                result.positionMs = positionMs
                return frames.map { ImmutableAudioFrame(positionMs - (endMs - it.timecode), it.data, it.volume, it.format) }
            }

            override fun onSourceStarted(startupMs: Long, prefixLeftFrames: Int) {
                if (prefixLeftFrames > 0) {
                    log.debug(
                        "handoff clone on guild {} produced audio after {}ms with {}ms of bridge left",
                        guildId, startupMs, prefixLeftFrames * 20L,
                    )
                } else {
                    // The bridge ran dry before the clone produced audio: a gap was heard.
                    log.info(
                        "handoff clone on guild {} took {}ms to produce audio; the {}ms bridge left a ~{}ms gap",
                        guildId, startupMs, result.bridgeMs, (startupMs - result.bridgeMs).coerceAtLeast(0L),
                    )
                }
            }
        }
        HandoffBridge.withPrefix(builder) { mainPlayer.play(handoff.clone) }
        if (result.skipped == null) {
            log.debug(
                "handoff bridge on guild {}: {}ms of mixed audio covers the clone's start (built in {}us)",
                guildId, result.bridgeMs, (System.nanoTime() - startedAt) / 1000L,
            )
        }
        return result
    }

    /** After an aborted/stale handoff, fall back to advancing the slot or idle. */
    private fun fallbackAdvanceOrIdle() {
        val hasNext = synchronized(lock) { readyNextTrack != null || pendingNextTrack != null }
        if (hasNext) {
            advanceNext()
        } else {
            synchronized(lock) {
                if (mainTrack() == null) {
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
            if (!xfadeActive && deck.track == null) return@abortXfade
            refreshBeatmatchStatusLocked()
            wasBeatMatched = xfadeBeatMatched
            fallbackReason = xfadeFallbackReason
            xfadeBeatMatched = false
            xfadeFallbackReason = null
            val subPosMs = deck.track?.let { safePosition(it) } ?: -1L
            val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
            val elapsed = if (deck.startMs > 0) System.currentTimeMillis() - deck.startMs else -1L
            val name = deck.track?.let { titleOf(it) } ?: "-"
            log.warn(
                "crossfade aborted on guild {} after {}ms: {} " +
                    "(sub '{}' subPos={}ms subOut={}ms pending={}f dropped={}f)",
                guildId, elapsed, reason, name, subPosMs, subOutMs,
                siphon.pendingFrames, siphon.droppedFrames,
            )
            xfadeActive = false
            activeRecipe = null
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
            xfadeFadeEndSample = -1L
            xfadeMainTrack = null
            val armed = deck.track
            deck.track = null
            protocol.armedHandoffId = null
            subNorm.setNow(1f)
            deck.gen++
            siphon.clear()
            deck.mainGain.rampTo(1f, msToSamples(duckFadeMs))
            deck.subGain.setNow(0f)
            Triple(deck.player, armed, name)
        }
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {} (was '{}')", guildId, subName, e)
        }
        deck.factory?.resetSpeed()
        notifyMixerEvent("overlapEnd") {
            put("reason", "aborted")
            put("detail", reason)
            put("beatMatched", wasBeatMatched)
            fallbackReason?.let { put("fallbackReason", it) }
            putTrack(armedSub)
        }
    }

    /** Plays the queued track now, seeking past analyzed leading silence. */
    private fun advanceNext(expected: TransitionFence? = null) {
        data class GaplessPlan(val next: AudioTrack, val epochAtStart: Long, val seqAtStart: Long, val revision: Long, val encodedTrack: String?, val preserve: Boolean, val leadMs: Long?, val requestedStartMs: Long)
        val plan = synchronized(lock) {
            if (expected != null && !matchesFenceLocked(expected)) return
            val n = readyNextTrack ?: pendingNextTrack ?: return
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
                knownNormGainLocked(n)?.let { mainNorm.setNow(it) }
            }
            GaplessPlan(n, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), slotRevision, enc, protocol.preserveTransition, null, requestedStartMs)
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
        deck.mainGain.setNow(1f)
        deck.subGain.setNow(0f)
    }

    // ---- silence analysis ----

    private fun trimForLocked(track: AudioTrack): TrimPoints = AnalysisCaches.trimFor(track)

    private fun leadForLocked(track: AudioTrack): Long = maxOf(
        trimForLocked(track).leadMs,
        idOf(track)?.let { AnalysisCaches.featuresHead[it]?.leadingSilenceMs } ?: 0L,
    )
    private fun trailForLocked(track: AudioTrack): Long = maxOf(
        trimForLocked(track).trailMs,
        idOf(track)?.let { AnalysisCaches.featuresTail[it]?.trailingSilenceMs } ?: 0L,
    )

    private fun normGainForLocked(track: AudioTrack): Float =
        AnalysisCaches.normGainFor(track, targetLufs, normGainBaseline)

    /** Gain from the full or provisional loudness of [track]; null while neither is known. */
    private fun knownNormGainLocked(track: AudioTrack): Float? =
        AnalysisCaches.knownNormGainFor(track, targetLufs, normGainBaseline)

    /**
     * Starting normalization gain for an incoming crossfade track. An unmeasured
     * track inherits the outgoing level, so a late analysis ramps it rather than
     * making it enter at unity and then drop.
     */
    private fun incomingNormGainLocked(next: AudioTrack): Float =
        if (normalizationEnabled) knownNormGainLocked(next) ?: mainNorm.target else 1f
    fun peekNextTrim(): TrimPoints? = synchronized(lock) {
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        trimForLocked(next).takeIf { it != TrimPoints.NONE }
    }

    data class CurrentTempo(val identifier: String, val bpm: Float)

    data class TrackKey(val identifier: String, val analysis: KeyAnalysis)

    /** Identity and estimate are captured together; an unknown estimate still represents a completed scan. */
    fun currentKey(): TrackKey? = synchronized(lock) {
        if (!keyDetectionEnabled) return null
        val track = mainTrack() ?: return null
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
        val track = mainTrack() ?: return null
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

    fun peekNextBeatGrid(): ExtendedBeatGrid? = synchronized(lock) {
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



    internal fun scheduleBaselineAnalysis(track: AudioTrack) = analysis.scheduleBaselineAnalysis(track)

    fun maybeRetryAnalysis(): AudioTrack? = analysis.maybeRetryAnalysis()

    /** Bounded, cache-only warmup; it never arms a queue slot or mutates playback. */
    fun requestPreanalysis(
        track: AudioTrack,
        includeNormalization: Boolean = analysis.shouldWarmNormFor(track),
    ): Boolean = analysis.requestPreanalysis(track, includeNormalization)

    /** Scans a clone on the isolated analysis player. A concurrent scan defers this work. */
    fun analyzeBlocking(
        track: AudioTrack,
        cacheOnly: Boolean = false,
        includeSilence: Boolean = !cacheOnly,
        includeNormalization: Boolean = !cacheOnly,
    ) = analysis.analyzeBlocking(track, cacheOnly, includeSilence, includeNormalization)

    /** Ramps the playing and overlapping track toward a newly known loudness. */
    private fun applyAnalyzedNormGain(identifier: String) {
        synchronized(lock) {
            if (!normalizationEnabled) return
            val playing = mainTrack()
            if (playing != null && idOf(playing) == identifier) {
                knownNormGainLocked(playing)?.let { mainNorm.rampTo(it, msToSamples(NORM_RAMP_MS)) }
            }
            val activeSub = deck.track
            if (xfadeActive && activeSub != null && idOf(activeSub) == identifier) {
                knownNormGainLocked(activeSub)?.let { subNorm.rampTo(it, msToSamples(NORM_RAMP_MS)) }
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
        /** The idle carrier outlives the last announcement by this long, bridging back-to-back requests and the audible tail. */
        internal const val IDLE_CARRIER_GRACE_MS = 1_000L
        /** Per-announcement gain bound; non-finite or negative values fall back to unity/zero. */
        internal const val MAX_ANNOUNCE_GAIN = 4f
        internal fun sanitizeAnnounceGain(gain: Float): Float =
            if (gain.isNaN()) 1f else gain.coerceIn(0f, MAX_ANNOUNCE_GAIN)
        /** Normalization gain changes from late loudness results glide over this span. */
        internal const val NORM_RAMP_MS = 3_000L

        /** Max time past the window opening to wait for incoming-track analysis before overlapping anyway. */
        private const val FEATURE_WAIT_MS = 2000L

        /** Lavaplayer's default frame buffer; used when the manager can't say. */

        /**
         * How long a FINISHED/LOAD_FAILED main track counts as "the armed track,
         * just ended" for the handoff guard; see [lastEndedMain].
         */
        private const val LAST_ENDED_MAIN_GRACE_MS = 10_000L

        fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)
    }
}
