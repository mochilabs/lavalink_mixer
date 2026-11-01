package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.IPlayer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Narrow view into [GuildMixer]'s transition/queue core used by [ProtocolController].
 * All `*Locked` methods are called while holding [lock]; teardown/mutation calls
 * run outside [lock] to preserve the original lock ordering.
 */
internal interface MixerCore {
    val guildId: Long
    val lock: Any
    val playerMutationLock: Any
    val playerManager: AudioPlayerManager

    val mainPlayer: IPlayer?
    val playingTrack: AudioTrack?
    val crossfadeMs: Long
    val isXfadeActive: Boolean
    val isOverlayActive: Boolean

    fun safePosition(track: AudioTrack): Long
    fun clearNextLocked()
    fun preloadArmedTrackLocked(track: AudioTrack)
    fun scheduleArmedAnalysis(track: AudioTrack)
    fun clearMaskingLocked()
    fun restoreUnityGainAfterStopLocked()
    fun syncPlayingStateOnResetLocked(playing: AudioTrack?)
    fun abortXfadeOutsideLock(reason: String)
    fun cancelOverlayOutsideLock(): Boolean
    fun notifyEvent(type: String, body: JsonObjectBuilder.() -> Unit)
}

/**
 * v2 control-plane state machine (`arm` / `invalidate` / `override` / `reset` /
 * `checkJitWindow` / `notifyAdvanced`). Shares [MixerCore.lock] and
 * [MixerCore.playerMutationLock] with [GuildMixer] so lock ordering is unchanged.
 */
internal class ProtocolController(
    private val core: MixerCore,
) {
    val gate = EpochGate(core.guildId)

    @Volatile var playbackState: PlaybackState = PlaybackState.IDLE
        internal set

    var armedTrack: AudioTrack? = null
        internal set
    var armedEncodedTrack: String? = null
        internal set
    var preserveTransition: Boolean = false
        internal set
    var armedEpoch: Long = 0L
        internal set
    var armedHandoffId: String? = null
        internal set
    var lastCountedTrack: AudioTrack? = null
        internal set

    @Volatile private var needNextFiredAtSeq: Long = -1L
    val advanceSeq = AtomicLong(0L)
    /** Last advanceSeq whose MixerNeedNextTrackEvent was actually dispatched (dedup). */
    @Volatile private var needNextDispatchedAtSeq: Long = -1L

    // Order-sensitive submissions (override STOP/PLAY_NOW, invalidate refires,
    // JIT delayed fires): a single-thread scheduled executor preserves FIFO order
    // and schedules 50ms/500ms settle delays without blocking immediate overrides.
    private val actionQueue = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mixer-v2-action").apply { isDaemon = true }
    }

    fun clearArmedLocked() {
        armedTrack = null
        preserveTransition = false
        armedEncodedTrack = null
        armedHandoffId = null
    }

    fun shutdown() {
        try {
            actionQueue.shutdownNow()
        } catch (e: Exception) {
            log.warn("actionQueue shutdown failed on guild {}", core.guildId, e)
        }
    }

    fun encodeTrackBase64(track: AudioTrack): String = try {
        // Lavaplayer's DefaultAudioPlayerManager.encodeTrack serializes track.position
        // in the trailing 8 bytes. Clone and zero the position whenever non-zero so the
        // emitted Base64 matches the canonical position=0 track encoded at load time.
        val target = if (core.safePosition(track) != 0L) {
            runCatching { track.makeClone().apply { position = 0L } }.getOrDefault(track)
        } else {
            track
        }
        val baos = ByteArrayOutputStream()
        core.playerManager.encodeTrack(MessageOutput(baos), target)
        Base64.getEncoder().encodeToString(baos.toByteArray())
    } catch (e: Exception) {
        track.identifier
    }

    fun arm(
        epoch: Long,
        idemKey: String,
        track: AudioTrack?,
        isEmpty: Boolean,
        rawEncodedTrack: String? = null,
        preserveTransition: Boolean = false,
    ): ArmResult {
        if (!isEmpty && track == null) {
            return ArmResult.BadRequest("arm with isEmpty=false requires a non-null encodedTrack")
        }
        val canonicalEncoded = if (isEmpty) null else (rawEncodedTrack ?: track?.let { encodeTrackBase64(it) })
        val fp = "arm|${canonicalEncoded ?: "empty"}|preserve=$preserveTransition"

        val abortNeeded: Boolean
        synchronized(core.lock) {
            when (val g = gate.checkAndRecord(epoch, idemKey, fp)) {
                is GateResult.Duplicate -> return ArmResult.Duplicate
                is GateResult.Stale -> return ArmResult.Stale(g.lastKnown)
                is GateResult.PreResetArrival -> return ArmResult.PreResetArrival(g.windowOpenAt, g.lastKnownEpoch)
                is GateResult.PayloadMismatch -> return ArmResult.PayloadMismatch
                else -> {}
            }
            abortNeeded = core.isXfadeActive
        }
        // Tear down an active overlap outside the lock BEFORE arming/analyzing the
        // replacement track so the stale subTrack cannot hand off and analyzeBlocking
        // does not see xfadeActive=true.
        if (abortNeeded) {
            core.abortXfadeOutsideLock("re-armed by client")
        }

        return synchronized(core.lock) {
            if (gate.lastKnownEpoch != epoch) {
                // A newer control command superseded us while abortXfade was running.
                return@synchronized ArmResult.Success(playbackState, gate.lastKnownEpoch)
            }
            if (isEmpty || track == null) {
                core.clearNextLocked()
                clearArmedLocked()
                armedEpoch = epoch
                playbackState = PlaybackState.NO_NEXT
                return@synchronized ArmResult.Success(playbackState, epoch)
            }

            core.clearNextLocked()
            this.preserveTransition = preserveTransition
            armedTrack = track
            armedEncodedTrack = canonicalEncoded
            armedEpoch = epoch
            armedHandoffId = UUID.randomUUID().toString()
            playbackState = PlaybackState.ARMED

            core.preloadArmedTrackLocked(track)
            core.scheduleArmedAnalysis(track)

            ArmResult.Success(playbackState, epoch)
        }
    }

    fun invalidate(epoch: Long, idemKey: String): InvalidateResult {
        var abortNeeded = false
        var refired = false
        synchronized(core.lock) {
            when (val g = gate.checkAndRecord(epoch, idemKey, "invalidate")) {
                is GateResult.Duplicate -> return InvalidateResult.Duplicate
                is GateResult.Stale -> return InvalidateResult.Stale(g.lastKnown)
                is GateResult.PreResetArrival -> return InvalidateResult.PreResetArrival(g.windowOpenAt, g.lastKnownEpoch)
                is GateResult.PayloadMismatch -> return InvalidateResult.PayloadMismatch
                else -> {}
            }

            core.clearNextLocked()
            clearArmedLocked()
            if (playbackState == PlaybackState.ARMED || playbackState == PlaybackState.NO_NEXT) {
                playbackState = PlaybackState.PLAYING
            }

            if (playbackState == PlaybackState.PLAYING) {
                val playing = core.playingTrack
                if (playing != null) {
                    val pos = core.safePosition(playing)
                    val dur = playing.duration
                    val tWindow = core.crossfadeMs + 7000L
                    if (dur > 0 && (dur - pos <= tWindow || dur <= tWindow)) {
                        refired = true
                        val refireSeq = advanceSeq.get()
                        needNextFiredAtSeq = refireSeq
                        val now = System.currentTimeMillis()
                        val delayMs = maxOf(50L, if (now < gate.windowOpenAt) gate.windowOpenAt - now + 20L else 50L)
                        actionQueue.schedule({
                            val still = synchronized(core.lock) {
                                if (playbackState == PlaybackState.PLAYING &&
                                    core.playingTrack === playing &&
                                    advanceSeq.get() == refireSeq &&
                                    needNextDispatchedAtSeq != refireSeq
                                ) {
                                    needNextDispatchedAtSeq = refireSeq
                                    true
                                } else {
                                    false
                                }
                            }
                            if (still) {
                                sendNeedNext(dur - core.safePosition(playing), playing)
                            }
                        }, delayMs, TimeUnit.MILLISECONDS)
                    }
                }
            }
            abortNeeded = core.isXfadeActive
        }
        if (abortNeeded) core.abortXfadeOutsideLock("invalidated by client")
        return InvalidateResult.Success(playbackState, epoch, refired)
    }

    fun override(
        epoch: Long,
        idemKey: String,
        action: String,
        newTrack: AudioTrack?,
        rawEncodedTrack: String? = null,
    ): OverrideResult {
        if (action != "STOP" && action != "PLAY_NOW") {
            return OverrideResult.BadRequest("Invalid action: $action")
        }
        if (action == "PLAY_NOW" && newTrack == null) {
            return OverrideResult.BadRequest("PLAY_NOW requires a non-null track")
        }

        val canonicalEncoded = newTrack?.let { rawEncodedTrack ?: encodeTrackBase64(it) } ?: ""
        val fp = "override|$action|$canonicalEncoded"
        var abortNeeded = false
        var cancelOverlayNeeded = false
        val overrideSeq: Long
        synchronized(core.lock) {
            when (val g = gate.checkAndRecord(epoch, idemKey, fp)) {
                is GateResult.Duplicate -> return OverrideResult.Duplicate
                is GateResult.Stale -> return OverrideResult.Stale(g.lastKnown)
                is GateResult.PreResetArrival -> return OverrideResult.PreResetArrival(g.windowOpenAt, g.lastKnownEpoch)
                is GateResult.PayloadMismatch -> return OverrideResult.PayloadMismatch
                else -> {}
            }

            core.clearNextLocked()
            clearArmedLocked()
            core.clearMaskingLocked()
            lastCountedTrack = newTrack
            overrideSeq = advanceSeq.incrementAndGet()

            when (action) {
                "STOP" -> playbackState = PlaybackState.IDLE
                "PLAY_NOW" -> playbackState = PlaybackState.PLAYING
            }
            abortNeeded = core.isXfadeActive
            if (action == "STOP" && core.isOverlayActive) cancelOverlayNeeded = true
        }

        if (abortNeeded) {
            core.abortXfadeOutsideLock("overridden by client ($action)")
            if (action == "STOP" && !cancelOverlayNeeded) {
                synchronized(core.lock) { core.restoreUnityGainAfterStopLocked() }
            }
        }
        if (cancelOverlayNeeded) core.cancelOverlayOutsideLock()

        actionQueue.execute {
            synchronized(core.playerMutationLock) {
                val superseded = synchronized(core.lock) {
                    gate.lastKnownEpoch > epoch && advanceSeq.get() != overrideSeq
                }
                if (superseded) {
                    log.warn("override ({}, epoch={}) superseded before execution on guild {}", action, epoch, core.guildId)
                    return@execute
                }
                var success = false
                try {
                    when (action) {
                        "STOP" -> {
                            core.mainPlayer?.stop()
                            success = true
                        }
                        "PLAY_NOW" -> {
                            newTrack?.let {
                                core.mainPlayer?.play(it)
                                success = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.warn("override action {} execution failed on guild {}", action, core.guildId, e)
                } finally {
                    if (success) {
                        core.notifyEvent("MixerOverriddenEvent") {
                            put("epoch", epoch)
                            put("requestId", idemKey)
                            put("action", action)
                        }
                    }
                }
            }
        }

        return OverrideResult.Success(playbackState, epoch)
    }

    fun reset(epoch: Long, idemKey: String, windowOpenGraceMs: Long = 1500L): ResetResult {
        var abortNeeded = false
        var cancelOverlayNeeded = false
        val resolvedEpoch = synchronized(core.lock) {
            when (val res = gate.recordReset(epoch, idemKey, windowOpenGraceMs)) {
                is ResetResult.Duplicate -> return res
                is ResetResult.PayloadMismatch -> return res
                is ResetResult.Success -> {}
            }
            core.clearNextLocked()
            clearArmedLocked()
            core.clearMaskingLocked()
            advanceSeq.incrementAndGet()

            val playing = core.playingTrack
            playbackState = if (playing != null) PlaybackState.PLAYING else PlaybackState.IDLE
            core.syncPlayingStateOnResetLocked(playing)

            abortNeeded = core.isXfadeActive
            cancelOverlayNeeded = core.isOverlayActive
            gate.lastKnownEpoch
        }
        if (abortNeeded) core.abortXfadeOutsideLock("reset by client")
        if (cancelOverlayNeeded) core.cancelOverlayOutsideLock()
        return ResetResult.Success(resolvedEpoch)
    }

    fun checkJitWindow() {
        val playing = core.playingTrack ?: return
        if (playbackState != PlaybackState.PLAYING) return
        val seq = advanceSeq.get()
        if (needNextFiredAtSeq == seq || needNextDispatchedAtSeq == seq) return

        val pos = core.safePosition(playing)
        val duration = playing.duration
        val tWindow = core.crossfadeMs + 7000L

        if (duration > 0 && (duration - pos <= tWindow || duration <= tWindow)) {
            val waitForWindowMs: Long
            synchronized(core.lock) {
                if (needNextFiredAtSeq == seq || needNextDispatchedAtSeq == seq) return
                needNextFiredAtSeq = seq
                val now = System.currentTimeMillis()
                waitForWindowMs = if (now < gate.windowOpenAt) gate.windowOpenAt - now + 20L else 0L
            }
            val baseDelayMs = if (duration <= tWindow) 500L else 0L
            val delayMs = maxOf(baseDelayMs, waitForWindowMs)
            val fireTask = Runnable {
                val still = synchronized(core.lock) {
                    if (playbackState == PlaybackState.PLAYING &&
                        core.playingTrack === playing &&
                        advanceSeq.get() == seq &&
                        needNextDispatchedAtSeq != seq
                    ) {
                        needNextDispatchedAtSeq = seq
                        true
                    } else {
                        false
                    }
                }
                if (still) {
                    sendNeedNext(duration - core.safePosition(playing), playing)
                }
            }
            if (delayMs > 0) {
                actionQueue.schedule(fireTask, delayMs, TimeUnit.MILLISECONDS)
            } else {
                actionQueue.execute(fireTask)
            }
        }
    }

    private fun sendNeedNext(remainingMs: Long, currentTrack: AudioTrack) {
        core.notifyEvent("MixerNeedNextTrackEvent") {
            put("remainingMs", remainingMs)
            put("currentTrackEncoded", encodeTrackBase64(currentTrack))
        }
    }

    fun notifyAdvanced(
        handoffType: String,
        expectedEpoch: Long = gate.lastKnownEpoch,
        expectedSeq: Long = advanceSeq.get(),
        encodedOverride: String? = null,
    ) {
        val track = core.playingTrack ?: return
        val handoffId: String? = synchronized(core.lock) {
            val id = if (handoffType == "CROSSFADE") armedHandoffId else null
            armedHandoffId = null
            id
        }
        val superseded = synchronized(core.lock) {
            gate.lastKnownEpoch != expectedEpoch || advanceSeq.get() != expectedSeq
        }
        if (superseded) {
            log.warn("{} advance superseded on guild {}; not emitting MixerAdvancedEvent", handoffType, core.guildId)
            return
        }
        synchronized(core.lock) {
            if (track !== lastCountedTrack) {
                lastCountedTrack = track
                advanceSeq.incrementAndGet()
            }
        }
        val encoded = encodedOverride ?: encodeTrackBase64(track)
        core.notifyEvent("MixerAdvancedEvent") {
            put("epoch", expectedEpoch)
            put("encodedTrack", encoded)
            put("handoffType", handoffType)
            handoffId?.let { put("handoffId", it) }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ProtocolController::class.java)
    }
}
