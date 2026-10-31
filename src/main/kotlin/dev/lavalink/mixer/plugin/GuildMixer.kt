package dev.lavalink.mixer.plugin

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

    fun updateBpm(enabled: Boolean, scanMs: Long? = null) {
        synchronized(lock) {
            bpmDetectionEnabled = enabled
            scanMs?.let { bpmScanMs = it.coerceIn(5000, 60000) }
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
    @Volatile var tailWatch: Boolean = false
    /** Tempo (BPM) detection is opt-in; each preloaded track decodes one head window for onset analysis. */
    @Volatile var bpmDetectionEnabled: Boolean = config.bpmDetectionEnabled
    @Volatile var bpmScanMs: Long = config.bpmScanMs.coerceIn(5000, 60000)

    // ---- Protocol State ----
    private val protocol = ProtocolController(object : MixerCore {
        override val guildId: Long get() = this@GuildMixer.guildId
        override val lock: Any get() = this@GuildMixer.lock
        override val playerMutationLock: Any get() = this@GuildMixer.playerMutationLock
        override val playerManager: AudioPlayerManager get() = this@GuildMixer.playerManager
        override val mainPlayer: IPlayer? get() = main
        override val playingTrack: AudioTrack? get() = main?.audioPlayer?.playingTrack
        override val crossfadeMs: Long get() = this@GuildMixer.crossfadeMs
        override val isXfadeActive: Boolean get() = xfadeActive
        override val isOverlayActive: Boolean get() = overlayActive

        override fun safePosition(track: AudioTrack): Long = this@GuildMixer.safePosition(track)

        override fun clearNextLocked() {
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
    /** Sequential (single-sub) overlay is still active; mutually exclusive with parallel overlays. */
    private var sequentialOverlayId: Long? = null
    /** Voice thread reads these without taking [lock]; races only shift a fade by a chunk. */
    @Volatile private var overlayActive: Boolean = false
    @Volatile private var xfadeActive: Boolean = false
    private var xfadeHandoffMs: Long = -1L
    /** Main-track instance the armed overlap belongs to; a handoff must never fire after that track was replaced. */
    private var xfadeMainTrack: AudioTrack? = null
    /** Position within [subTrack] that has reached the audible output. */
    private val subOutputSamples = AtomicLong(0L)
    private var subStartPositionMs: Long = 0L
    private var announceToken: Long = 0L
    private var masking: Boolean = false
    private var maskingTrack: AudioTrack? = null
    /** Bumped on every sub arming/teardown; in-flight startOverlap/startOverlay re-check it. */
    private var subGen: Long = 0L

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
    private val underrunWarnAt = AtomicLong(0L)
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
                    val targetGain = if (overlayActive || parallelOverlays.isNotEmpty()) duckLevel.coerceIn(0f, 1f) else 1f
                    StartSnap(
                        xfadeActive,
                        if (silenceSkipEnabled) leadForLocked(track) else -1L,
                        fadeInMs,
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
                        snap.leadMs.coerceIn(0, (duration - 500).coerceAtLeast(0))
                    } else snap.leadMs
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
            val handoff = synchronized(lock) {
                // The player has already null'ed playingTrack by now (lavaplayer
                // clears it before dispatching this event); remember the ended
                // instance so doHandoff can tell a natural finish from a replace.
                lastEndedMain = track
                lastEndedMainAt = System.currentTimeMillis()
                xfadeActive
            }
            if (handoff) {
                doHandoff()
            } else {
                val hasNext = synchronized(lock) { readyNextTrack != null || pendingNextTrack != null }
                if (hasNext) {
                    advanceNext()
                } else {
                    // Nothing queued to advance: only go IDLE when the player
                    // really has no track left (client may already have
                    // started the replacement, which TrackStart will report).
                    synchronized(lock) {
                        if (main?.audioPlayer?.playingTrack == null) {
                            protocol.playbackState = PlaybackState.IDLE
                        }
                    }
                }
            }
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' stuck on guild {} (threshold {}ms); mixer failover", title, guildId, thresholdMs)
            recoverFromMainStall()
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            val currentMain = synchronized(lock) { main?.audioPlayer }
            if (currentMain != null && player !== currentMain) return
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' failed on guild {}; mixer failover", title, guildId, exception)
            recoverFromMainStall()
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
            val xfade = synchronized(lock) { xfadeActive }
            if (xfade) abortXfade("sub track ended: $endReason")
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
        val previousPlayers: List<AudioPlayer>
        synchronized(lock) {
            val newAudioPlayer = player.audioPlayer
            previousPlayers = attachedPlayers.filter { it !== newAudioPlayer }
            for (old in previousPlayers) {
                old.removeListener(mainListener)
                attachedPlayers.remove(old)
            }
            main = player
            if (attachedPlayers.add(newAudioPlayer)) {
                newAudioPlayer.addListener(mainListener)
            }
            if (previousPlayers.isNotEmpty()) {
                // Release the stale session's filter feeder so the new session's
                // filter takes over immediately and syncs chainPos to the new track.
                electedFeeder.set(0L)
            }
        }
        for (old in previousPlayers) {
            try {
                old.stopTrack()
            } catch (e: Exception) {
                log.debug("stopping replaced session player failed on guild {}: {}", guildId, e.toString())
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
            val start = xfadeStartSample
            val end = xfadeEndSample
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
        } else {
            mainGain.advance(samples)
            subGain.advance(samples)
        }
        return Gains(mainGain.current * mainNormGain, subGain.current * subNormGain)
    }

    fun takeSub(samples: Int, channels: Int, out: Array<FloatArray>) = siphon.take(samples, channels, out)

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
        val active = synchronized(lock) { parallelOverlays.values.filter { it.active } }
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
     * Throttled voice-thread warning: an overlap is fading in but the
     * secondary decoder did not supply the whole chunk, so the mix dropped
     * to silence on the sub side for this frame.
     */
    fun noteUnderrun(missing: Int, requested: Int) {
        if (!xfadeActive || missing <= 0) return
        val now = System.currentTimeMillis()
        val last = underrunWarnAt.get()
        if (now - last < 1000 || !underrunWarnAt.compareAndSet(last, now)) return
        log.warn(
            "crossfade sub starvation on guild {}: {} of {} samples silent " +
                "(pending={}f/{}ms dropped={}f/{}ms subOut={}ms)",
            guildId, missing, requested,
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
        if (tailWatch && silenceSkipEnabled) SilenceAnalyzer.thresholdAmp(silenceThresholdDb) else null

    fun noteChunk(peak: Float, samples: Int, thresholdAmp: Float) = tailCutter.note(peak, samples, thresholdAmp)

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
        subOutputSamples.set(0L)
        xfadeActive = true
    }

    internal fun endXfadeForTest() {
        xfadeActive = false
    }

    /** Test seam: fully reset overlap state without touching sub listeners. */
    internal fun clearXfadeForTest() {
        synchronized(lock) {
            xfadeActive = false
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
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

    /** Test seam: simulate [count] active parallel slots without players (for the limit check). */
    internal fun armParallelForTest(count: Int) {
        synchronized(lock) {
            parallelOverlays.clear()
            repeat(count) { i ->
                parallelOverlays[i.toLong()] = OverlaySlot(i.toLong(), playerManager.createPlayer(), SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())).apply { active = true }
            }
        }
    }

    // ---- scheduler entry points ----

    /** Keeps the secondary decoder flowing; its PCM is captured by the siphon filter. */
    fun drain() {
        val sub = synchronized(lock) { subPlayer?.takeIf { it.playingTrack != null } }
        if (sub != null) {
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

    private sealed interface PollAction {
        data object None : PollAction
        data class StartOverlap(val plan: CrossfadePlan) : PollAction
        data object Handoff : PollAction
        data object AdvanceNext : PollAction
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
        // Read the transition state before acting on it: doHandoff clears it.
        if (xfadeActive) logXfadeProgress(action)
        when (action) {
            is PollAction.None -> Unit
            is PollAction.StartOverlap -> startOverlap(action.plan)
            is PollAction.Handoff -> doHandoff()
            is PollAction.AdvanceNext -> advanceNext()
            is PollAction.AbortXfade -> abortXfade(action.reason)
        }
        checkJitWindow()
    }

    /** 1Hz breadcrumb while an overlap runs: is the sub actually feeding us? */
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
        log.info(
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
        val audioPlayer = main?.audioPlayer ?: return PollAction.None
        val track = audioPlayer.playingTrack ?: run {
            lastMainTrack = null
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
            resetFilterHeartbeatLocked(pos)
            syncChainPos(pos)
        }
        lastPosition = pos
        checkFilterHeartbeatLocked(audioPlayer, pos)
        // Opportunistic promotion: analysis may have finished since queue.
        promoteIfPendingLocked()
        if (!xfadeActive && (readyNextTrack != null || pendingNextTrack != null)) {
            tailWatch = silenceSkipEnabled
            val duration = track.duration
            if (duration > 0 && duration < Long.MAX_VALUE / 2) {
                val trail = if (silenceSkipEnabled) trailForLocked(track) else 0L
                val contentEnd = TrimTransition.contentEnd(duration, trail)
                val plan = if (track.isSeekable && crossfadeEnabled) {
                    TrimTransition.crossfade(contentEnd, crossfadeMs)
                } else null
                if (readyNextTrack != null && plan != null &&
                    chainPos.get() >= plan.startMs * SAMPLE_RATE / 1000
                ) {
                    // Chain, not track.position: the mix lands on frames as they
                    // are decoded, which runs a frame buffer ahead of playback.
                    return PollAction.StartOverlap(plan)
                } else if (silenceSkipEnabled && trail > 0 && pos >= contentEnd) {
                    // End exactly at the last audible sample; do not emit the file's tail silence.
                    // Force-promote pending so a slow analysis never plays silence.
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext
                } else if (silenceSkipEnabled &&
                    tailCutter.shouldCut(pos, duration, tailConfirmMs, silenceTailScanMs)
                ) {
                    // Realtime fallback: sustained silence inside the tail window.
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext
                } else if (readyNextTrack == null && pendingNextTrack != null && pos >= duration - 500) {
                    // Analysis still running at file end: advance anyway (degraded, no trim).
                    promoteForcedLocked()
                    return PollAction.AdvanceNext
                }
            } else if (silenceSkipEnabled &&
                tailCutter.shouldCut(pos, -1, tailConfirmMs, silenceTailScanMs)
            ) {
                // Streams have no pre-analysis tail; realtime is the only signal.
                if (readyNextTrack == null) promoteForcedLocked()
                return PollAction.AdvanceNext
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
            tailWatch = false
            return PollAction.None
        }
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

    fun isOverlayActive(): Boolean = synchronized(lock) { overlayActive }
    fun isXfadeActive(): Boolean = synchronized(lock) { xfadeActive }
    fun isSubBusy(): Boolean = synchronized(lock) { overlayActive || xfadeActive || analysisState.active || parallelOverlays.isNotEmpty() }

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
        isSequentialBusy = { overlayActive || xfadeActive },
    )

    /** Queues an announcement; returns its id and 1-based position among queued announcements. */
    fun enqueueAnnouncement(
        identifier: String?,
        encodedTrack: String?,
        duckLevel: Float,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
    ): Pair<Long, Int> =
        synchronized(lock) {
            announcementQueue.enqueue(identifier, encodedTrack, duckLevel, mode)
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
    ): AudioTrack {
        if (mode == AnnounceMode.PARALLEL) {
            startParallelOverlay(track, duck, announcementId)
            return track
        }
        val (sub, gen) = synchronized(lock) {
            if (overlayActive || xfadeActive) throw MixerBusyException("secondary player busy on guild $guildId")
            val s = ensureSubLocked()
            announceToken++
            subTrack = track
            overlayActive = true
            sequentialOverlayId = announcementId
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
    private fun startParallelOverlay(track: AudioTrack, duck: Float, announcementId: Long?) {
        val slot = synchronized(lock) {
            if (parallelOverlays.size >= maxParallelOverlays) {
                throw MixerBusyException("parallel overlay limit ($maxParallelOverlays) reached on guild $guildId")
            }
            val id = announcementId ?: announcementQueue.nextId()
            val player = playerManager.createPlayer()
            val siphon = SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())
            player.setFilterFactory(SiphonFactory(siphon))
            player.addListener(parallelOverlayListener(id))
            val s = OverlaySlot(id, player, siphon)
            parallelOverlays[id] = s
            s.track = track
            s.active = true
            s.ramp.setNow(0f)
            s.ramp.rampTo(1f, msToSamples(150))
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
            protocol.clearArmedLocked()
            subTrack = null
            xfadeActive = false
            overlayActive = false
            analysisState.active = false
            val p = parallelOverlays.values.toList()
            parallelOverlays.clear()
            subPlayer.also { subPlayer = null } to p
        }
        try {
            sub?.destroy()
        } catch (e: Exception) {
            log.warn("sub destroy failed on guild {}", guildId, e)
        }
        analysisService.destroy()
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
            val factory = SiphonFactory(siphon)
            siphonFactory = factory
            sub.setFilterFactory(factory)
            sub.addListener(subListener)
            subPlayer = sub
        }
        return sub
    }


    private fun startOverlap(plan: CrossfadePlan) {
        // Pop the slot first; seeks and playTrack run outside the lock.
        val (next, epochAtStart, seqAtStart) = synchronized(lock) {
            val n = readyNextTrack ?: pendingNextTrack ?: return
            readyNextTrack = null
            pendingNextTrack = null
            Triple(n, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get())
        }
        seekPastLead(next)
        // Pre-warm the secondary decoder BEFORE arming the fade. playTrack()
        // returns after Lavaplayer fills the whole frame buffer; draining a
        // burst here lets the siphon bank up to a frame-buffer's worth of PCM
        // so the overlap's opening seconds are actually audible — previously
        // the sub side started with a near-empty siphon (pending=1s,
        // dropped=22k+ frames, subOut lagging subPos by a full second).
        val sub = synchronized(lock) { ensureSubLocked() }
        try {
            sub.playTrack(next)
        } catch (e: Exception) {
            log.warn("overlap pre-start play failed on guild {}", guildId, e)
        }
        prefillSubSiphon(sub)
        val gen = synchronized(lock) {
            if (protocol.gate.lastKnownEpoch != epochAtStart || protocol.advanceSeq.get() != seqAtStart || protocol.playbackState != PlaybackState.ARMED) {
                return
            }
            if (overlayActive || xfadeActive) {
                // Lost a race with an overlay/overlap started on another thread:
                // re-queue rather than dropping the track.
                if (readyNextTrack == null) readyNextTrack = next
                else if (pendingNextTrack == null) pendingNextTrack = next
                else log.warn("dropping overlapped queue on guild {}: slot busy", guildId)
                return
            }
            subTrack = next
            overlayActive = false
            xfadeActive = true
            xfadeHandoffMs = plan.handoffMs
            xfadeMainTrack = main?.audioPlayer?.playingTrack
            subOutputSamples.set(0L)
            subStartPositionMs = safePosition(next)
            subStartMs = System.currentTimeMillis()
            siphon.clear()
            xfadeTotal = msToSamples(plan.durationMs).coerceAtLeast(1)
            xfadeLeft = xfadeTotal
            xfadeStartSample = plan.startMs * SAMPLE_RATE / 1000
            xfadeEndSample = plan.handoffMs * SAMPLE_RATE / 1000
            // Sub-track normalization gain: applied on top of the sin curve.
            subNormGain = if (normalizationEnabled) normGainForLocked(next) else 1f
            val startSample = xfadeStartSample
            if (chainPos.get() > startSample) {
                log.warn(
                    "crossfade on guild {} armed {}ms after its window opened " +
                        "(chain at {}ms, window {}ms..{}ms); the fade starts late",
                    guildId, (chainPos.get() - startSample) * 1000L / SAMPLE_RATE,
                    chainPos.get() * 1000L / SAMPLE_RATE, plan.startMs, plan.handoffMs,
                )
            }
            xfadeLogMs = 0L
            ++subGen
        }
        // A control command (override/invalidate/reset/arm) that aborted the fade
        // while playTrack was in flight bumps subGen and stops an empty sub —
        // the playTrack above then decodes a zombie into a torn-down slot.
        // Detect and unwind it: stop the decoder and only restore if not superseded by control plane.
        val stillCurrent = synchronized(lock) { subGen == gen && xfadeActive && subTrack === next }
        if (!stillCurrent) {
            log.warn("overlap superseded during start on guild {}; unwinding '{}'", guildId, titleOf(next))
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("superseded overlap stop failed on guild {}", guildId, e)
            }
            siphon.clear()
            synchronized(lock) {
                // Only restore if the same arm epoch/seq is still active (e.g. transient sub contention),
                // never when superseded by invalidate/override/reset/re-arm.
                if (protocol.gate.lastKnownEpoch == epochAtStart && protocol.advanceSeq.get() == seqAtStart && protocol.playbackState == PlaybackState.ARMED) {
                    if (readyNextTrack == null) readyNextTrack = next
                    else if (pendingNextTrack == null) pendingNextTrack = next
                    else log.warn("dropping superseded overlap on guild {}: slot busy", guildId)
                }
            }
            return
        }
        log.info(
            "crossfade started on guild {} at {}ms; handoff at {}ms ({}ms audible overlap); " +
                "sub track '{}' @{}ms, mixerFilterCalls={}",
            guildId, plan.startMs, plan.handoffMs, plan.durationMs,
            titleOf(next), subStartPositionMs, filterRunCount.get(),
        )
        val mainPosMs = main?.audioPlayer?.playingTrack?.let { safePosition(it) } ?: 0L
        notifyMixerEvent("overlapStart") {
            put("crossfadeMs", plan.durationMs)
            put("handoffInMs", (plan.handoffMs - mainPosMs).coerceAtLeast(0L))
            putTrack(next)
        }
    }

    /**
     * Pumps the just-started secondary decoder so the siphon banks a
     * frame-buffer's worth of PCM before the fade begins mixing it.
     * playTrack() returns once Lavaplayer has decoded the frame buffer full;
     * without this drain that audio stays inside the sub player's own frame
     * buffer and the siphon only fills at live-decode speed, leaving the
     * overlap's opening chunk starved (subOut lagging subPos by ~1s).
     */
    private fun prefillSubSiphon(sub: AudioPlayer) {
        val frame = newSubProvideFrame()
        try {
            var burst = 0
            val deadline = System.currentTimeMillis() + 2000
            while (burst < 600 && System.currentTimeMillis() < deadline) {
                if (!pumpSubFrame(sub, frame)) break
                burst++
            }
        } finally {
            frame.setBuffer(java.nio.ByteBuffer.allocate(0))
        }
    }

    private fun notifyMixerEvent(type: String, body: JsonObjectBuilder.() -> Unit) =
        sendMixerEvent(main, guildId, type, body)

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
        data class Plan(val handoff: Handoff?, val epochAtStart: Long, val seqAtStart: Long)
        val plan = synchronized(lock) {
            // poll(), mainListener.onTrackEnd and recoverFromMainStall all read
            // xfadeActive under the lock and then call us *after* releasing it.
            // A main track hitting FINISHED at the exact handoff position can
            // therefore race the position-based handoff; without this guard the
            // second caller replays the whole transition and throws away the
            // mask ramp it just armed.
            if (!xfadeActive) return@doHandoff
            val sub = subTrack
            val armedEnc = protocol.armedEncodedTrack
            protocol.armedTrack = null
            protocol.armedEncodedTrack = null
            xfadeActive = false
            // The handoff is a transition into the incoming track, not an end
            // of playback: the state must reflect audio that keeps flowing.
            protocol.playbackState = PlaybackState.PLAYING
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
            xfadeMainTrack = null
            val epochAtStart = protocol.gate.lastKnownEpoch
            val seqAtStart = protocol.advanceSeq.get()
            if (sub == null) {
                resetGainsLocked()
                Plan(null, epochAtStart, seqAtStart)
            } else {
                val clone = sub.makeClone()
                val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
                Plan(
                    Handoff(
                        source = sub,
                        clone = clone,
                        encodedTrack = armedEnc,
                        outputPosition = subStartPositionMs + subOutMs,
                        subReportedMs = safePosition(sub),
                        subOutMs = subOutMs,
                        droppedFrames = siphon.droppedFrames,
                    ),
                    epochAtStart,
                    seqAtStart,
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
                siphon.clear()
                log.warn("crossfade handoff superseded on guild {}; dropping '{}'", guildId, titleOf(handoff.source))
                return
            }
            try {
                sub?.stopTrack()
            } catch (e: Exception) {
                log.warn("sub stop failed on guild {}", guildId, e)
            }
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
                log.info(
                    "crossfade handoff on guild {} at {}ms (sub decoder was at {}ms, " +
                        "{}ms of sub audio heard, {} frames dropped)",
                    guildId, handoff.outputPosition, handoff.subReportedMs, handoff.subOutMs, handoff.droppedFrames,
                )
                notifyMixerEvent("overlapEnd") {
                    put("reason", "handoff")
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
        val (sub, armedSub, subName) = synchronized(lock) {
            // Idempotent: nothing armed means a duplicate/late abort (e.g. a
            // deliberate sub.stopTrack() racing the listener) must not replay
            // teardown, clobber a handoff mask ramp, or spam overlapEnd.
            if (!xfadeActive && subTrack == null) return@abortXfade
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
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
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
        notifyMixerEvent("overlapEnd") {
            put("reason", "aborted")
            put("detail", reason)
            putTrack(armedSub)
        }
    }

    private fun finishOverlay() {
        val sub = synchronized(lock) {
            if (!overlayActive) return
            overlayActive = false
            subTrack = null
            sequentialOverlayId = null
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
        val slot = synchronized(lock) { parallelOverlays.remove(id) } ?: return
        slot.active = false
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
    private fun advanceNext() {
        data class GaplessPlan(val next: AudioTrack, val epochAtStart: Long, val seqAtStart: Long, val encodedTrack: String?)
        val plan = synchronized(lock) {
            val n = readyNextTrack ?: pendingNextTrack ?: return
            val enc = protocol.armedEncodedTrack
            readyNextTrack = null
            pendingNextTrack = null
            protocol.armedTrack = null
            protocol.armedEncodedTrack = null
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
            GaplessPlan(n, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), enc)
        }
        seekPastLead(plan.next)
        synchronized(playerMutationLock) {
            val committedSeq = synchronized(lock) {
                if (protocol.gate.lastKnownEpoch != plan.epochAtStart || protocol.advanceSeq.get() != plan.seqAtStart) {
                    null
                } else {
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

    private fun seekPastLead(track: AudioTrack) {
        if (!silenceSkipEnabled) return
        val lead = synchronized(lock) { leadForLocked(track) }
        if (lead > 200 && track.isSeekable) {
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

    /** Cached tempo of the queued next track; null while detection is pending or disabled. */
    fun peekNextBpm(): BpmDetector.BpmResult? = synchronized(lock) {
        if (!bpmDetectionEnabled) return null
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        val id = idOf(next) ?: return null
        AnalysisCaches.bpm[id]
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
            }
        }
    }

    fun maybeRetryAnalysis(): AudioTrack? {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val pending = pendingNextTrack ?: return null
            val id = idOf(pending) ?: return null
            val needSilence = silenceSkipEnabled && !AnalysisCaches.trim.contains(id)
            val needNorm = normalizationEnabled && !AnalysisCaches.norm.contains(id)
            if (!needSilence && !needNorm) return null
            if (!analysisState.canRetryLocked(id, now)) return null
            log.debug("retrying deferred silence analysis for '{}' on guild {}", titleOf(pending), guildId)
            try {
                pending.makeClone()
            } catch (e: Exception) {
                log.debug("analysis retry clone failed on guild {}: {}", guildId, e.toString())
                null
            }
        }
    }

    /**
     * Decodes [track] (a clone; the original is untouched) on the secondary
     * player and measures leading/trailing silence into [trimCache].
     * When [normalizationEnabled], also accumulates loudness for the whole
     * track and stores the computed gain in [normCache].
     * Runs on the shared analysis thread; safe to call from a future callback.
     * Skipped while the sub player is otherwise in use.
     */
    fun analyzeBlocking(track: AudioTrack) {
        val doSilence = silenceSkipEnabled
        val doNorm = normalizationEnabled
        val doBpm = bpmDetectionEnabled
        if (!doSilence && !doNorm && !doBpm) {
            synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        val identifier = idOf(track) ?: run {
            synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        synchronized(lock) {
            val hasSilence = !doSilence || AnalysisCaches.trim.contains(identifier)
            val hasNorm = !doNorm || AnalysisCaches.norm.contains(identifier)
            val hasBpm = !doBpm || AnalysisCaches.bpm.contains(identifier)
            if (hasSilence && hasNorm && hasBpm) {
                promoteIfPendingLocked()
                return
            }
            if (analysisState.active) {
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
            val hasBpm = doBpm && !AnalysisCaches.bpm.contains(identifier)
            val result = analysisService.analyze(
                track,
                TrackAnalysisSettings(
                    silence = hasSilence,
                    normalization = hasNorm,
                    bpm = hasBpm,
                    silenceThresholdDb = silenceThresholdDb,
                    silenceMinSoundMs = silenceMinSoundMs,
                    silenceHeadScanMs = silenceHeadScanMs,
                    silenceTailScanMs = silenceTailScanMs,
                    bpmScanMs = bpmScanMs,
                ),
            )
            if (hasNorm && result.loudnessRms != null) {
                AnalysisCaches.norm[identifier] = result.loudnessRms
                applyAnalyzedNormGain(identifier)
                log.info("normalization guild {}: lufs={}", guildId, result.loudnessRms)
            }
            if (hasBpm && result.bpm != null) {
                AnalysisCaches.bpm[identifier] = result.bpm
                log.info(
                    "bpm analysis guild {}: bpm={} firstBeat={}ms confidence={}",
                    guildId,
                    result.bpm.bpm,
                    result.bpm.firstBeatMs,
                    result.bpm.confidence,
                )
            }
            if (result.aborted) {
                log.warn("silence analysis aborted on guild {}: sub decoder failed", guildId)
                return
            }
            synchronized(lock) {
                if (hasSilence) {
                    AnalysisCaches.trim[identifier] = result.trim ?: TrimPoints.NONE
                }
                analysisState.clearFailureIfMatchingLocked(identifier)
                promoteIfPendingLocked(identifier)
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
    ): ArmResult = protocol.arm(epoch, idemKey, track, isEmpty, rawEncodedTrack)

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
