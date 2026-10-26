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

data class Gains(val main: Float, val sub: Float)
enum class PlaybackState { IDLE, PLAYING, ARMED, NO_NEXT }

sealed class GateResult {
    object Ok : GateResult()
    object Duplicate : GateResult()
    data class Stale(val lastKnown: Long) : GateResult()
    object PreResetArrival : GateResult()
    object PayloadMismatch : GateResult()
    object InProgress : GateResult()
}

sealed class ArmResult {
    data class Success(val state: PlaybackState, val epoch: Long) : ArmResult()
    object Duplicate : ArmResult()
    data class Stale(val lastKnown: Long) : ArmResult()
    object PreResetArrival : ArmResult()
    object PayloadMismatch : ArmResult()
}

sealed class InvalidateResult {
    data class Success(val state: PlaybackState, val epoch: Long, val refiredNeedNext: Boolean) : InvalidateResult()
    object Duplicate : InvalidateResult()
    data class Stale(val lastKnown: Long) : InvalidateResult()
    object PreResetArrival : InvalidateResult()
}

sealed class OverrideResult {
    data class Success(val state: PlaybackState, val epoch: Long) : OverrideResult()
    object Duplicate : OverrideResult()
    data class Stale(val lastKnown: Long) : OverrideResult()
    object PreResetArrival : OverrideResult()
    object PayloadMismatch : OverrideResult()
    data class BadRequest(val message: String) : OverrideResult()
}

sealed class ResetResult {
    data class Success(val lastKnownEpoch: Long) : ResetResult()
}


/**
 * One concurrent `mode=parallel` TTS overlay. Each slot owns its own
 * secondary [AudioPlayer] and [SiphonQueue]; its decoded PCM is summed into
 * the main chain by [MixerFilter] alongside the (single) crossfade sub.
 * The [ramp] fades the slot in/out on start/finish so a parallel overlay
 * never clicks; the music duck level is handled by [GuildMixer]'s shared
 * [GuildMixer.mainGain], not per-slot.
 */
class OverlaySlot(
    val id: Long,
    val player: AudioPlayer,
    val siphon: SiphonQueue,
) {
    @Volatile var track: AudioTrack? = null
    @Volatile var active: Boolean = false
    val ramp: GainRamp = GainRamp(0f)
}

class MixerBusyException(message: String) : RuntimeException(message)

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

    @Volatile var silenceSkipEnabled: Boolean = config.silenceSkipEnabled
    @Volatile var silenceThresholdDb: Float = config.silenceThresholdDb
    @Volatile var silenceMinSoundMs: Long = config.silenceMinSoundMs
    @Volatile var silenceHeadScanMs: Long = config.silenceHeadScanMs
    @Volatile var silenceTailScanMs: Long = config.silenceTailScanMs
    /** Sustained realtime silence needed before an early cut. */
    @Volatile var tailConfirmMs: Long = config.tailConfirmMs
    /** Set by poll(): realtime tail watch armed (cheap per-chunk check reads this). */
    @Volatile var tailWatch: Boolean = false

    // ---- Protocol State ----
    @Volatile var playbackState: PlaybackState = PlaybackState.IDLE
        private set
    @Volatile var lastKnownEpoch: Long = 0L
        private set
    @Volatile var windowOpenAt: Long = 0L
        private set
    private var armedTrack: AudioTrack? = null
    private var armedEpoch: Long = 0L
    private var armedHandoffId: String? = null
    @Volatile private var needNextFiredAtSeq: Long = -1L
    @Volatile private var advanceSeq: Long = 0L

    private val recentRequestIds = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 64
    }

    private val actionQueue = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "mixer-v2-action").apply { isDaemon = true }
    }

    // ---- volume normalization ----

    /** Track id → measured RMS (first track anchors the baseline). */
    private val normCache = LinkedHashMap<String, Float>()
    /** Normalization gain for the currently playing main track; read lock-free by the voice thread. */
    @Volatile private var mainNormGain: Float = 1f
    /** Normalization gain for the armed sub (crossfade) track; 1 while no overlap is armed. */
    @Volatile private var subNormGain: Float = 1f
    /**
     * Gain that anchors every track to the first track's loudness.
     * Zero until the first track's background analysis completes; after
     * that every queued track's gain is computed as `fromRms(trackRms, baselineRms)`.
     */
    @Volatile private var normGainBaseline: Float = 0f

    private val tailCutter = TailCutDecider()

    private var pendingNextTrack: AudioTrack? = null
    private var readyNextTrack: AudioTrack? = null
    private var attachedPlayers = mutableSetOf<AudioPlayer>()

    private var subPlayer: AudioPlayer? = null
    private var siphonFactory: SiphonFactory? = null
    private var analysisActive: Boolean = false
    /** Throttle for analysis deferred because the sub player was busy at preload time. */
    @Volatile private var analysisRetryAtMs: Long = 0L
    /** Consecutive analysis failures for [analysisFailureId]; retries give up after 3. */
    private var analysisFailures: Int = 0
    private var analysisFailureId: String? = null
    private val trimCache = LinkedHashMap<String, TrimPoints>()
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

    /**
     * Two Lavaplayer players can exist for one guild across two socket
     * sessions (a resuming session racing a fresh restore): both filter
     * chains then resolve to this mixer and, unchecked, every ~20ms chunk
     * advances [chainPos] twice — the counter ran ahead of real playback at
     * exactly 2x and armed crossfades half a song early, while both chains
     * drained the shared sub siphon into "crossfade sub starvation".
     *
     * Only the elected filter may mutate mixer state (chainPos, ramps,
     * siphons); a rival chain passes audio through untouched. Election
     * transfers when the incumbent retires ([MixerFilter.close], which
     * Lavaplayer calls on hot-swap and track end) or goes silent for
     * [feederStaleMs].
     */
    private val electedFeeder = AtomicLong(0L)
    private val electedSeenAtMs = AtomicLong(0L)
    private val rivalWarnAtMs = AtomicLong(0L)
    /** Test seam; production budget is ~50 voice-thread chunks of silence. */
    @Volatile var feederStaleMs: Long = 1000L

    /**
     * Decode-side position of the main track: exactly what [MixerFilter] has
     * already mixed. [track.position] is the *output* position and trails this
     * by the frame-buffer backlog (frameBufferDuration, 5000ms by default), so
     * arming an overlap off `track.position` waits until the outgoing track is
     * already decoded - by then there is no audio left to blend, and the
     * crossfade degenerates into a cut. [advance] moves it; [syncChainPos]
     * re-bases it on track start and seeks.
     */
    private val chainPos = AtomicLong(0L)
    private var filterRunBaseline = 0L
    private var filterBaselinePos = 0L
    @Volatile private var filterMissing: Boolean = false
    private var filterMissingWarned: Boolean = false
    private val underrunWarnAt = AtomicLong(0L)
    /** Only touched from [poll], which the single mixer-loop thread drives. */
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
    /** Set when the sub decoder reports stuck/exception mid-analysis so scans abort early. */
    @Volatile private var analysisBroken: Boolean = false

    val siphon = SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())
    private val drainFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    private data class StartSnap(val xfade: Boolean, val leadMs: Long, val fadeInMs: Long)

    val mainListener = object : AudioEventAdapter() {
        override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
            // Snapshot under lock; the (potentially blocking) seek runs outside it
            // so the voice thread never stalls behind us.
            val snap = synchronized(lock) {
                tailCutter.reset()
                if (masking) {
                    masking = false
                    null
                } else {
                    StartSnap(
                        xfadeActive,
                        if (silenceSkipEnabled) leadForLocked(track) else -1L,
                        fadeInMs,
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
                    normGainBaseline == 0f && subPlayer == null && !analysisActive
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
                mainGain.rampTo(1f, msToSamples(snap.fadeInMs))
            }
            // After the lead-skip seek above: this is where the chain resumes.
            syncChainPos(track.position)
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            if (endReason != AudioTrackEndReason.FINISHED && endReason != AudioTrackEndReason.LOAD_FAILED) return
            // Decide under lock, act outside it (play/seek may block on source I/O).
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
                if (hasNext) advanceNext()
            }
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' stuck on guild {} (threshold {}ms); mixer failover", title, guildId, thresholdMs)
            recoverFromMainStall()
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
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
        val hasNext = synchronized(lock) { readyNextTrack != null || pendingNextTrack != null }
        if (hasNext) advanceNext()
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
                    analysisActive -> {
                        // Unblock analyzeBlocking scans waiting on tap data that will never arrive.
                        analysisBroken = true
                        0
                    }
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
        synchronized(lock) {
            main = player
            if (attachedPlayers.add(player.audioPlayer)) {
                player.audioPlayer.addListener(mainListener)
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
                    warnRivalFeeder(feederId, elected, silentMs)
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

    private fun warnRivalFeeder(feederId: Long, elected: Long, silentMs: Long) {
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

    /**
     * Heartbeat from the voice path: the mixer filter processed a chunk.
     * [checkFilterHeartbeatLocked] uses the absence of these to prove the
     * filter never made it into the running track's chain.
     */
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
            synchronized(lock) { normCache[trackId] = trackRms }
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
        synchronized(lock) { trimCache[identifier] }

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
                    chainPos.get() >= plan.startMs * SAMPLE_RATE / 1000 && !analysisActive
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

    /**
     * A main track whose position keeps advancing while [filterRunCount]
     * never moves means Lavaplayer built this chain without our filter: the
     * client sent `play` before `filters`, or never sent `pluginFilters.mixer`
     * at all. Lavaplayer does not hot-swap filter factories into a running
     * track, so the crossfade then only ever computes gains nobody applies —
     * the exact symptom of "track A plays to the end, B starts afterwards
     * from zero". Say so loudly instead of failing silently.
     */
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
    fun isSubBusy(): Boolean = synchronized(lock) { overlayActive || xfadeActive || analysisActive || parallelOverlays.isNotEmpty() }

    // ---- announcement queue ----
    //
    // TTS/announcements are talk-over overlays: they duck the music rather than
    // becoming a track in the playback order. One overlay at a time is a hard
    // limit (there is a single secondary decoder), so successive announcements
    // wait here and play back to back as the secondary player frees up.

    data class AnnouncementItem(
        val id: Long,
        val identifier: String?,
        val encodedTrack: String?,
        val duckLevel: Float,
        val mode: AnnounceMode,
    )

    enum class AnnounceMode { SEQUENTIAL, PARALLEL }

    private val announcementQueue = ArrayDeque<AnnouncementItem>()
    private var announcementSeq: Long = 0L
    /** A queued item is being resolved/started on the plugin's thread; prevents double starts. */
    private var announcementResolving: Boolean = false

    /** Queues an announcement; returns its id and 1-based position among queued announcements. */
    fun enqueueAnnouncement(
        identifier: String?,
        encodedTrack: String?,
        duckLevel: Float,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
    ): Pair<Long, Int> =
        synchronized(lock) {
            val item = AnnouncementItem(++announcementSeq, identifier, encodedTrack, duckLevel, mode)
            announcementQueue.addLast(item)
            log.info("queued announcement {} on guild {} ({} waiting)", item.id, guildId, announcementQueue.size)
            item.id to announcementQueue.size
        }

    /**
     * Pops the next announcement when a slot is free to start it. A
     * `mode=SEQUENTIAL` item needs the single secondary player free (no
     * overlay, no crossfade); a `mode=PARALLEL` item only needs a free
     * parallel slot and so can start alongside the crossfade sub. The caller
     * resolves the track and calls [startOverlay]; on failure or a busy race
     * it must hand the item back via [requeueAnnouncement] or
     * [onAnnouncementFailed].
     */
    fun takeNextAnnouncement(): AnnouncementItem? = synchronized(lock) {
        if (announcementResolving) return null
        // A parallel item can start as long as a parallel slot is free,
        // even while the single sequential sub is busy (crossfade/overlay).
        if (parallelOverlays.size < maxParallelOverlays) {
            val idx = announcementQueue.indexOfFirst { it.mode == AnnounceMode.PARALLEL }
            if (idx >= 0) {
                val item = announcementQueue.removeAt(idx)
                announcementResolving = true
                return item
            }
        }
        // Sequential path: needs the single secondary player free. A parallel
        // item whose slot isn't free yet must wait, not degrade to sequential.
        if (overlayActive || xfadeActive) return null
        val item = announcementQueue.firstOrNull() ?: return null
        if (item.mode == AnnounceMode.PARALLEL) return null // wait for a free parallel slot
        announcementQueue.removeFirst()
        announcementResolving = true
        item
    }

    /** Puts an item back at the head after a busy race or transient failure. */
    fun requeueAnnouncement(item: AnnouncementItem, cause: Throwable?) {
        synchronized(lock) {
            announcementQueue.addFirst(item)
            announcementResolving = false
        }
        log.warn("announcement {} re-queued on guild {}: {}", item.id, guildId, cause?.toString() ?: "secondary player busy")
    }

    /** Drops an item whose track could not be resolved/started. */
    fun onAnnouncementFailed(item: AnnouncementItem, cause: Throwable?) {
        synchronized(lock) { announcementResolving = false }
        log.warn("announcement {} on guild {} dropped: {}", item.id, guildId, cause?.toString() ?: "unknown")
    }

    fun onAnnouncementStarted(item: AnnouncementItem) {
        synchronized(lock) { announcementResolving = false }
    }

    fun announcementQueueSize(): Int = synchronized(lock) { announcementQueue.size }

    /** Removes all queued (not active) announcements; returns how many were dropped. */
    fun clearAnnouncements(): Int = synchronized(lock) {
        val size = announcementQueue.size
        announcementQueue.clear()
        if (size > 0) {
            log.info("cleared {} queued announcement(s) on guild {}", size, guildId)
        }
        size
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
            analysisActive -> "analyzing"
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
        val sub = synchronized(lock) {
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
            s
        }
        try {
            sub.playTrack(track)
        } catch (e: Exception) {
            log.warn("overlay play failed on guild {}", guildId, e)
            finishOverlay()
            throw e
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
            val id = announcementId ?: ++announcementSeq
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
            val p = parallelOverlays.values.toList()
            parallelOverlays.clear()
            subPlayer.also { subPlayer = null } to p
        }
        try {
            sub?.destroy()
        } catch (e: Exception) {
            log.warn("sub destroy failed on guild {}", guildId, e)
        }
        for (slot in parallels) {
            try {
                slot.player.destroy()
            } catch (e: Exception) {
                log.warn("parallel overlay destroy failed on guild {}", guildId, e)
            }
        }
        try {
            actionQueue.shutdownNow()
        } catch (e: Exception) {
            log.warn("actionQueue shutdown failed on guild {}", guildId, e)
        }
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

    /** Separate buffer from [drainFrame]: analysis runs on its own thread. */
    private fun newProvideFrame(): MutableAudioFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    /** @return false when the sub's frame buffer had nothing to give. */
    private fun pumpAnalysis(sub: AudioPlayer, frame: MutableAudioFrame): Boolean = try {
        sub.provide(frame)
    } catch (e: Exception) {
        analysisBroken = true
        log.warn("analysis pump failed on guild {}", guildId, e)
        false
    }

    /**
     * Keeps the secondary decoder moving while a scan runs. Nothing else
     * does: Lavaplayer fills the frame buffer once (frameBufferDuration) and
     * then blocks until somebody calls provide(), and the mixer-loop's
     * [drain] only ticks ~50Hz on whatever happens to be playing. Without
     * this the scan collects a single buffer's worth of audio and then sits
     * on an empty tap until its 45s deadline, which either truncates the
     * trim or stalls every preload by 45 seconds. The frames pulled here are
     * discarded — the tap, not the frame buffer, is the scan's data source.
     */
    private fun pumpSub(sub: AudioPlayer, frame: MutableAudioFrame) {
        var burst = 0
        while (burst < ANALYSIS_PUMP_BURST && !analysisBroken) {
            if (!pumpAnalysis(sub, frame)) break
            burst++
        }
        try {
            Thread.sleep(if (burst > 0) ANALYSIS_PUMP_SLEEP_MS else ANALYSIS_IDLE_SLEEP_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            analysisBroken = true
        }
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
        val next = synchronized(lock) {
            val n = readyNextTrack ?: pendingNextTrack ?: return
            readyNextTrack = null
            pendingNextTrack = null
            n
        }
        seekPastLead(next)
        val sub = synchronized(lock) { ensureSubLocked() }
        synchronized(lock) {
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
        }
        try {
            sub.playTrack(next)
        } catch (e: Exception) {
            log.warn("overlap play failed on guild {}", guildId, e)
            abortXfade("overlap play failed")
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

    private fun titleOf(track: AudioTrack): String =
        try { track.info.title ?: track.info.identifier } catch (e: Exception) { "unknown" }

    /**
     * Pushes a custom `{op: "mixer"}` websocket message to the client session
     * owning the main player. Lavalink-client surfaces unknown ops on its
     * `raw` listener (and logs a stray "Unexpected op" error), so clients can
     * consume these without protocol changes.
     */
    private fun notifyMixerEvent(type: String, body: JsonObjectBuilder.() -> Unit) {
        val context = try {
            main?.socketContext
        } catch (e: Exception) {
            null
        } ?: return
        val payload = buildJsonObject {
            put("op", "mixer")
            put("type", type)
            put("guildId", guildId.toString())
            body()
        }
        try {
            context.sendMessage(payload)
        } catch (e: Exception) {
            log.debug("mixer event '{}' delivery failed on guild {}: {}", type, guildId, e.toString())
        }
    }

    private fun JsonObjectBuilder.putTrack(track: AudioTrack?) {
        if (track == null) return
        put(
            "track",
            buildJsonObject {
                put("identifier", idOf(track))
                put("title", try { track.info.title } catch (e: Exception) { null })
            }
        )
    }

    private data class Handoff(
        val source: AudioTrack,
        val clone: AudioTrack,
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
            // the overlap was not armed on.
            abortXfade("handoff arrived after the armed main track was replaced")
            return
        }
        val plan = synchronized(lock) {
            // poll(), mainListener.onTrackEnd and recoverFromMainStall all read
            // xfadeActive under the lock and then call us *after* releasing it.
            // A main track hitting FINISHED at the exact handoff position can
            // therefore race the position-based handoff; without this guard the
            // second caller replays the whole transition and throws away the
            // mask ramp it just armed.
            if (!xfadeActive) return
            val sub = subTrack
            xfadeActive = false
            xfadeHandoffMs = -1L
            xfadeStartSample = -1L
            xfadeEndSample = -1L
            xfadeMainTrack = null
            if (sub == null) {
                resetGainsLocked()
                null
            } else {
                val clone = sub.makeClone()
                val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
                Handoff(
                    source = sub,
                    clone = clone,
                    outputPosition = subStartPositionMs + subOutMs,
                    subReportedMs = safePosition(sub),
                    subOutMs = subOutMs,
                    droppedFrames = siphon.droppedFrames,
                )
            }
        } ?: return
        try {
            if (plan.clone.isSeekable) {
                val duration = plan.clone.duration
                plan.clone.position = if (duration > 0) {
                    plan.outputPosition.coerceIn(0, duration)
                } else {
                    plan.outputPosition.coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            log.warn("handoff seek failed on guild {}, starting from 0", guildId, e)
        }
        val sub = synchronized(lock) { subPlayer }
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
        // Arm the mask BEFORE play(): Lavaplayer dispatches TrackStart
        // synchronously from playTrack(), so a flag set afterwards is too late.
        // An unmasked onTrackStart re-seeks the clone to its leading-silence
        // offset, rewinding the whole overlap the crossfade just built.
        synchronized(lock) {
            // Only clear our own slot; a newer overlap may already reuse it.
            if (subTrack === plan.source) subTrack = null
            masking = true
        }
        try {
            // Volatile read: attachMain publishes the player safely.
            main?.play(plan.clone)
            log.info(
                "crossfade handoff on guild {} at {}ms (sub decoder was at {}ms, " +
                    "{}ms of sub audio heard, {} frames dropped)",
                guildId, plan.outputPosition, plan.subReportedMs, plan.subOutMs, plan.droppedFrames,
            )
            notifyMixerEvent("overlapEnd") {
                put("reason", "handoff")
                putTrack(plan.source)
            }
            notifyAdvanced("CROSSFADE")
        } catch (e: Exception) {
            log.warn("handoff play failed on guild {}", guildId, e)
            synchronized(lock) {
                if (subTrack === plan.source) subTrack = null
                masking = false
                resetGainsLocked()
            }
        }
    }

    private fun abortXfade(reason: String) {
        val (sub, armedSub, subName) = synchronized(lock) {
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
            subNormGain = 1f
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
        log.info("overlay finished on guild {}", guildId)
        notifyMixerEvent("announceEnd") {}
        val sub = synchronized(lock) {
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
        val next = synchronized(lock) {
            val n = readyNextTrack ?: pendingNextTrack ?: return
            readyNextTrack = null
            pendingNextTrack = null
            lastMainTrack = null
            tailCutter.reset()
            n
        }
        seekPastLead(next)
        try {
            main?.play(next)
            log.info("gapless advance on guild {}: now '{}'", guildId, titleOf(next))
            notifyAdvanced("GAPLESS")
        } catch (e: Exception) {
            log.warn("gapless play failed on guild {}", guildId, e)
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

    private fun idOf(track: AudioTrack): String? = try {
        track.identifier
    } catch (e: Exception) {
        null
    }

    private fun trimForLocked(track: AudioTrack): TrimPoints {
        val id = idOf(track) ?: return TrimPoints.NONE
        return trimCache[id] ?: TrimPoints.NONE
    }

    private fun leadForLocked(track: AudioTrack): Long = trimForLocked(track).leadMs
    private fun trailForLocked(track: AudioTrack): Long = trimForLocked(track).trailMs

    /**
     * Effective gain for a track: normalized to [targetLufs] (-14 LUFS standard).
     * If [normGainBaseline] is explicitly overridden (e.g. in test fixtures), it anchors
     * to that baseline; otherwise it normalizes directly against [targetLufs].
     */
    private fun normGainForLocked(track: AudioTrack): Float {
        val id = idOf(track) ?: return 1f
        val measuredLufs = normCache[id] ?: return 1f
        return if (normGainBaseline != 0f) {
            NormalizationGain.fromLufs(measuredLufs, targetLufs = normGainBaseline).gain
        } else {
            NormalizationGain.fromLufs(measuredLufs, targetLufs = targetLufs).gain
        }
    }

    fun peekNextTrim(): TrimPoints? = synchronized(lock) {
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        trimForLocked(next).takeIf { it != TrimPoints.NONE }
    }

    /** Moves pending → ready once its trim and gain are known (or no analysis needed). */
    private fun promoteIfPendingLocked() {
        val pending = pendingNextTrack ?: return
        val id = idOf(pending) ?: run {
            pendingNextTrack = null
            readyNextTrack = pending
            return
        }
        val silenceDone = !silenceSkipEnabled || trimCache.containsKey(id)
        val normDone = !normalizationEnabled || normCache.containsKey(id)
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

    private fun noteAnalysisFailureLocked(identifier: String) {
        if (analysisFailureId == identifier) analysisFailures++
        else {
            analysisFailureId = identifier
            analysisFailures = 1
        }
    }

    /**
     * Deferred analysis (silence + normalization). Preload-time scanning is
     * skipped while the secondary player is busy (overlay, overlap, another
     * scan), which used to strand the slot in pending until the end-of-file
     * fallback fired - an untrimmed advance with no crossfade. The mixer loop
     * calls this every tick; once the sub is free it returns a fresh clone to
     * scan, or null to wait. The end-of-file fallback still covers tracks
     * whose analysis keeps failing.
     */
    /**
     * Schedules a background loudness scan of the currently playing first
     * track so its RMS can become [normGainBaseline] before the next track
     * starts. The scan reuses the secondary player (hidden, no voice) and
     * therefore must wait until no crossfade/overlay occupies it.
     */
    internal fun scheduleBaselineAnalysis(track: AudioTrack) {
        val factory = synchronized(lock) {
            if (normGainBaseline != 0f || subPlayer != null || analysisActive) return
            analysisActive = true
            analysisBroken = false
            ensureSubLocked()
            siphonFactory
        } ?: return
        analysisExecutor.execute {
            val clone = try {
                track.makeClone()
            } catch (e: Exception) {
                synchronized(lock) { analysisActive = false }
                return@execute
            }
            try {
                val loudness = measureLoudnessBlocking(clone, factory)
                if (loudness != null) {
                    synchronized(lock) {
                        normCache[track.identifier] = loudness
                        while (normCache.size > 200) normCache.remove(normCache.keys.first())
                    }
                    log.info("normalization baseline guild {}: lufs={}", guildId, loudness)
                }

            } catch (e: Exception) {
                log.warn("baseline loudness scan failed on guild {}", guildId, e)
            } finally {
                synchronized(lock) { analysisActive = false }
            }
        }
    }

    fun maybeRetryAnalysis(): AudioTrack? {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val pending = pendingNextTrack ?: return null
            val id = idOf(pending) ?: return null
            val needSilence = silenceSkipEnabled && !trimCache.containsKey(id)
            val needNorm = normalizationEnabled && !normCache.containsKey(id)
            if (!needSilence && !needNorm) return null
            if (overlayActive || xfadeActive || analysisActive) return null
            if (analysisFailureId == id && analysisFailures >= 3) return null
            if (now - analysisRetryAtMs < 250) return null
            analysisRetryAtMs = now
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
        if (!doSilence && !doNorm) {
            synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        val identifier = idOf(track) ?: run {
            synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        synchronized(lock) {
            val hasSilence = !doSilence || trimCache.containsKey(identifier)
            val hasNorm = !doNorm || normCache.containsKey(identifier)
            if (hasSilence && hasNorm) {
                promoteIfPendingLocked()
                return
            }
            if (overlayActive || xfadeActive || analysisActive) {
                log.debug(
                    "analysis deferred on guild {}: sub player busy (overlay={} xfade={}); will retry",
                    guildId, overlayActive, xfadeActive,
                )
                return
            }
            analysisActive = true
            analysisBroken = false
        }
        try {
            val factory = synchronized(lock) { ensureSubLocked(); siphonFactory }
                ?: return
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
            val scanCap = maxOf(silenceHeadScanMs, silenceTailScanMs)
            val hasSilence = doSilence && !trimCache.containsKey(identifier)
            val hasNorm = doNorm && !normCache.containsKey(identifier)

            var lead = 0L
            var tail = 0L
            if (hasSilence || hasNorm) {
                // Short tracks: a single decode from the start yields both ends
                // and loudness.
                if (duration in 1..minOf(scanCap, 30000)) {
                    val result = analyzeSinglePass(track, factory, duration, measureLoudness = hasNorm)
                    lead = result.leadMs
                    tail = result.trailMs
                    if (hasNorm && result.loudnessRms != null) {
                        synchronized(lock) {
                            normCache[identifier] = result.loudnessRms
                            while (normCache.size > 200) normCache.remove(normCache.keys.first())
                        }
                        log.info("normalization guild {}: lufs={}", guildId, result.loudnessRms)
                    }

                } else {
                    if (hasSilence) {
                        lead = scanHead(track, factory)
                        if (seekable && duration > 0 && duration < Long.MAX_VALUE / 2) {
                            tail = scanTail(track, factory, duration)
                        }
                    }
                    if (hasNorm) {
                        // Loudness on a separate full-track decode so the
                        // silence scans' seeks/early stops never skew the RMS.
                        val loudness = measureLoudnessBlocking(track, factory)
                        if (loudness != null) {
                            synchronized(lock) {
                                normCache[identifier] = loudness
                                while (normCache.size > 200) normCache.remove(normCache.keys.first())
                            }
                            log.info("normalization guild {}: lufs={}", guildId, loudness)
                        }

                    }
                }
            }

            val guardedLead = if (duration > 0 && lead >= duration - 500) 0 else lead.coerceAtLeast(0)
            if (analysisBroken) {
                // The sub decoder died mid-scan; partial PCM would poison the
                // cache. Leave it uncached so a later preload retries, and let
                // poll() degrade to an untrimmed advance at content end.
                log.warn("silence analysis aborted on guild {}: sub decoder failed", guildId)
                return
            }
            synchronized(lock) {
                if (hasSilence) {
                    trimCache[identifier] = TrimPoints(guardedLead, tail.coerceAtLeast(0))
                    while (trimCache.size > 200) trimCache.remove(trimCache.keys.first())
                }
                if (analysisFailureId == identifier) {
                    analysisFailures = 0
                    analysisFailureId = null
                }
                promoteIfPendingLocked()
            }
            if (hasSilence) {
                log.info("silence analysis guild {}: lead={}ms trail={}ms", guildId, guardedLead, tail)
            }
        } catch (e: Exception) {
            log.warn("silence analysis failed on guild {}", guildId, e)
            synchronized(lock) { noteAnalysisFailureLocked(identifier) }
        } finally {
            synchronized(lock) {
                analysisActive = false
                try {
                    subPlayer?.stopTrack()
                } catch (e: Exception) {
                    log.warn("analysis cleanup stop failed on guild {}", guildId, e)
                }
            }
        }
    }

    /** Streams the head through [SilenceAnalyzer.LeadScanner]; stops decoding once confirmed. */
    private fun scanHead(track: AudioTrack, factory: SiphonFactory): Long {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = synchronized(lock) { subPlayer } ?: return 0
            val clone = track.makeClone()
            sub.playTrack(clone)
            val scanner = SilenceAnalyzer.LeadScanner(
                SilenceAnalyzer.thresholdAmp(silenceThresholdDb),
                msToSamples(silenceMinSoundMs),
            )
            val cap = msToSamples(silenceHeadScanMs)
            var collected = 0
            val pumpFrame = newProvideFrame()
            val deadline = System.currentTimeMillis() + 45000
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
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("head scan stop failed on guild {}", guildId, e)
            }
            return scanner.resultMs ?: 0
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun scanTail(track: AudioTrack, factory: SiphonFactory, duration: Long): Long {
        val startMs = (duration - silenceTailScanMs).coerceAtLeast(0)
        val collected = collectFrom(track, factory, startMs, msToSamples(silenceTailScanMs + 2000))
        if (collected.isEmpty()) return 0
        var total = 0L
        for (f in collected) total += f[0].size
        val collectedMs = total * 1000 / 48000
        val lastEnd = SilenceAnalyzer.findLastSound(
            collected,
            SilenceAnalyzer.thresholdAmp(silenceThresholdDb),
            msToSamples(silenceMinSoundMs),
        )
        // No sound in the whole window: the window itself is trailing silence.
        if (lastEnd < 0) return collectedMs
        return (collectedMs - lastEnd).coerceAtLeast(0)
    }

    private data class SinglePassResult(
        val leadMs: Long,
        val trailMs: Long,
        val loudnessRms: Float?,
    )

    private fun analyzeSinglePass(
        track: AudioTrack,
        factory: SiphonFactory,
        duration: Long,
        measureLoudness: Boolean = false,
    ): SinglePassResult {
        val collected = collectFrom(track, factory, 0, msToSamples(duration + 1000))
        if (collected.isEmpty()) return SinglePassResult(0L, 0L, if (measureLoudness) 0f else null)
        var total = 0L
        for (f in collected) total += f[0].size
        val totalMs = total * 1000 / 48000
        val threshold = SilenceAnalyzer.thresholdAmp(silenceThresholdDb)
        val minSound = msToSamples(silenceMinSoundMs)
        val first = SilenceAnalyzer.findFirstSound(collected, threshold, minSound)
        val lastEnd = SilenceAnalyzer.findLastSound(collected, threshold, minSound)
        val lead = if (first < 0) 0 else first
        val trail = if (lastEnd < 0) totalMs else (totalMs - lastEnd).coerceAtLeast(0)
        val loudness = if (measureLoudness) {
            val meter = LoudnessMeter()
            meter.feed(collected)
            meter.finish()
        } else null
        return SinglePassResult(lead, trail, loudness)
    }

    /** Streams the whole track through a tap and returns its RMS, or null on failure. */
    private fun measureLoudnessBlocking(track: AudioTrack, factory: SiphonFactory): Float? {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = synchronized(lock) { subPlayer } ?: return null
            val clone = track.makeClone()
            sub.playTrack(clone)
            val meter = LoudnessMeter()
            val pumpFrame = newProvideFrame()
            // Full-track loudness needs to cover the whole decode; a fixed
            // 45s cap would truncate a 3-minute track's RMS to its opening.
            val durationMs = try {
                clone.duration
            } catch (e: Exception) {
                -1L
            }
            val extraMs = 5_000L
            val capMs = if (durationMs in 1..(10 * 60_000L)) durationMs else 45_000L
            val deadline = System.currentTimeMillis() + capMs + extraMs
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
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("loudness scan stop failed on guild {}", guildId, e)
            }
            return meter.finish()
        } finally {
            factory.removeTap(tap)
        }
    }

    /**
     * Decodes [track] from [startMs] (seeking a clone first) into exact-length
     * chunks, up to [capSamples]. The clone is stopped before returning.
     */
    private fun collectFrom(
        track: AudioTrack,
        factory: SiphonFactory,
        startMs: Long,
        capSamples: Int,
    ): List<Array<FloatArray>> {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = synchronized(lock) { subPlayer } ?: return emptyList()
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
            val deadline = System.currentTimeMillis() + 45000
            while (collected < capSamples && System.currentTimeMillis() < deadline && !analysisBroken) {
                if (sub.playingTrack !== clone) break
                if (tap.pendingFrames == 0) {
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
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("scan stop failed on guild {}", guildId, e)
            }
            return frames
        } finally {
            factory.removeTap(tap)
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

    fun encodeTrackBase64(track: AudioTrack): String = try {
        val baos = java.io.ByteArrayOutputStream()
        playerManager.encodeTrack(com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput(baos), track)
        java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
    } catch (e: Exception) {
        track.identifier
    }

    private fun checkAndRecordLocked(epoch: Long, idemKey: String, fp: String): GateResult {
        val prev = recentRequestIds[idemKey]
        if (prev != null) {
            return if (prev == fp) GateResult.Duplicate else GateResult.PayloadMismatch
        }
        val now = System.currentTimeMillis()
        if (now < windowOpenAt) return GateResult.PreResetArrival
        if (epoch < lastKnownEpoch) return GateResult.Stale(lastKnownEpoch)
        recentRequestIds[idemKey] = fp
        lastKnownEpoch = epoch
        return GateResult.Ok
    }

    fun arm(epoch: Long, idemKey: String, track: AudioTrack?, isEmpty: Boolean): ArmResult = synchronized(lock) {
        val fp = "arm|${track?.let { encodeTrackBase64(it) } ?: "empty"}"
        when (val g = checkAndRecordLocked(epoch, idemKey, fp)) {
            is GateResult.Duplicate -> return ArmResult.Duplicate
            is GateResult.Stale -> return ArmResult.Stale(g.lastKnown)
            is GateResult.PreResetArrival -> return ArmResult.PreResetArrival
            is GateResult.PayloadMismatch -> return ArmResult.PayloadMismatch
            else -> {}
        }

        if (isEmpty || track == null) {
            clearNext()
            armedTrack = null
            armedEpoch = epoch
            playbackState = PlaybackState.NO_NEXT
            return ArmResult.Success(playbackState, epoch)
        }

        // ARMED or NO_NEXT or PLAYING -> 교체 장전
        clearNext()
        armedTrack = track
        armedEpoch = epoch
        armedHandoffId = java.util.UUID.randomUUID().toString()
        playbackState = PlaybackState.ARMED

        // Preload / analysis trigger
        readyNextTrack = null
        pendingNextTrack = track
        promoteIfPendingLocked()

        ArmResult.Success(playbackState, epoch)
    }

    fun invalidate(epoch: Long, idemKey: String): InvalidateResult = synchronized(lock) {
        when (val g = checkAndRecordLocked(epoch, idemKey, "invalidate")) {
            is GateResult.Duplicate -> return InvalidateResult.Duplicate
            is GateResult.Stale -> return InvalidateResult.Stale(g.lastKnown)
            is GateResult.PreResetArrival -> return InvalidateResult.PreResetArrival
            else -> {}
        }

        clearNext()
        armedTrack = null
        if (playbackState == PlaybackState.ARMED || playbackState == PlaybackState.NO_NEXT) {
            playbackState = PlaybackState.PLAYING
        }

        var refired = false
        if (playbackState == PlaybackState.PLAYING) {
            val playing = main?.audioPlayer?.playingTrack
            if (playing != null) {
                val pos = safePosition(playing)
                val dur = playing.duration
                val tWindow = crossfadeMs + 7000L
                if (dur > 0 && (dur - pos <= tWindow || dur <= tWindow)) {
                    refired = true
                    actionQueue.execute {
                        Thread.sleep(50)
                        sendNeedNext(dur - pos, playing)
                    }
                }
            }
        }
        InvalidateResult.Success(playbackState, epoch, refired)
    }

    fun override(epoch: Long, idemKey: String, action: String, newTrack: AudioTrack?): OverrideResult {
        if (action != "STOP" && action != "PLAY_NOW") {
            return OverrideResult.BadRequest("Invalid action: $action")
        }
        if (action == "PLAY_NOW" && newTrack == null) {
            return OverrideResult.BadRequest("PLAY_NOW requires a non-null track")
        }

        val fp = "override|$action|${newTrack?.let { encodeTrackBase64(it) } ?: ""}"
        synchronized(lock) {
            when (val g = checkAndRecordLocked(epoch, idemKey, fp)) {
                is GateResult.Duplicate -> return OverrideResult.Duplicate
                is GateResult.Stale -> return OverrideResult.Stale(g.lastKnown)
                is GateResult.PreResetArrival -> return OverrideResult.PreResetArrival
                is GateResult.PayloadMismatch -> return OverrideResult.PayloadMismatch
                else -> {}
            }

            clearNext()
            armedTrack = null
            advanceSeq++

            when (action) {
                "STOP" -> playbackState = PlaybackState.IDLE
                "PLAY_NOW" -> playbackState = PlaybackState.PLAYING
            }
        }

        // Execute audio mutation outside lock on actionQueue
        actionQueue.execute {
            var success = false
            try {
                when (action) {
                    "STOP" -> {
                        main?.stop()
                        success = true
                    }
                    "PLAY_NOW" -> {
                        newTrack?.let {
                            main?.play(it)
                            success = true
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("override action {} execution failed on guild {}", action, guildId, e)
            } finally {
                if (success) {
                    notifyMixerEvent("MixerOverriddenEvent") {
                        put("epoch", epoch)
                        put("requestId", idemKey)
                        put("action", action)
                    }
                }
            }
        }

        return OverrideResult.Success(playbackState, epoch)
    }

    fun reset(epoch: Long, idemKey: String, windowOpenGraceMs: Long = 1500L): ResetResult = synchronized(lock) {
        if (epoch < lastKnownEpoch) {
            log.warn("reset epoch {} < lastKnown {} on guild {}; keeping {}", epoch, lastKnownEpoch, guildId, lastKnownEpoch)
        }
        lastKnownEpoch = maxOf(lastKnownEpoch, epoch)
        recentRequestIds.clear()
        windowOpenAt = if (windowOpenGraceMs > 0) System.currentTimeMillis() + windowOpenGraceMs else 0L
        armedTrack = null
        advanceSeq++

        val isPlaying = main?.audioPlayer?.playingTrack != null
        playbackState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.IDLE

        ResetResult.Success(lastKnownEpoch)
    }

    fun checkJitWindow() {
        val playing = main?.audioPlayer?.playingTrack ?: return
        val seq = advanceSeq
        if (needNextFiredAtSeq == seq) return

        val pos = safePosition(playing)
        val duration = playing.duration
        val tWindow = crossfadeMs + 7000L

        if (duration > 0 && (duration - pos <= tWindow || duration <= tWindow)) {
            synchronized(lock) {
                if (needNextFiredAtSeq == seq) return
                needNextFiredAtSeq = seq
            }
            val delayMs = if (duration <= tWindow) maxOf(500L, duration - tWindow) else 0L
            actionQueue.execute {
                if (delayMs > 0) Thread.sleep(delayMs)
                sendNeedNext(duration - pos, playing)
            }
        }
    }

    private fun sendNeedNext(remainingMs: Long, currentTrack: AudioTrack) {
        notifyMixerEvent("MixerNeedNextTrackEvent") {
            put("remainingMs", remainingMs)
            put("currentTrackEncoded", encodeTrackBase64(currentTrack))
        }
    }

    fun notifyAdvanced(handoffType: String) {
        val track = main?.audioPlayer?.playingTrack ?: return
        advanceSeq++
        notifyMixerEvent("MixerAdvancedEvent") {
            put("epoch", lastKnownEpoch)
            put("encodedTrack", encodeTrackBase64(track))
            put("handoffType", handoffType)
            armedHandoffId?.let { put("handoffId", it) }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GuildMixer::class.java)
        private const val SAMPLE_RATE = 48000

        /** Lavaplayer's default frame buffer; used when the manager can't say. */
        private const val DEFAULT_FRAME_BUFFER_MS = 5000
        private const val ANALYSIS_PUMP_BURST = 8
        private const val ANALYSIS_PUMP_SLEEP_MS = 10L
        private const val ANALYSIS_IDLE_SLEEP_MS = 20L

        /**
         * How long a FINISHED/LOAD_FAILED main track counts as "the armed track,
         * just ended" for the handoff guard; see [lastEndedMain].
         */
        private const val LAST_ENDED_MAIN_GRACE_MS = 10_000L

        fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)
    }
}
