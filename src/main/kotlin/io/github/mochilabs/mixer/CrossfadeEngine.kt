package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Identity of a planned transition; any change to these fields means the plan is stale. */
internal data class TransitionFence(val outgoing: AudioTrack?, val incoming: AudioTrack?, val epoch: Long, val seq: Long, val slotRevision: Long)

/** What [CrossfadeEngine] needs from its guild mixer. */
internal interface CrossfadeHost {
    val guildId: Long
    /** The guild state lock; every `...Locked` member here must be called while holding it. */
    val lock: Any
    /** Outer lock serializing main-player mutations. Never acquired while holding [lock]. */
    val playerMutationLock: Any
    val mainPlayer: IPlayer?
    val playingTrack: AudioTrack?
    /** A sequential overlay holds the deck. */
    val overlayActive: Boolean
    /** [GuildMixer.destroy] ran; no new players may be created. */
    val destroyedLocked: Boolean
    /** Decode-side position of the main chain, in samples. */
    val chainPosSamples: Long
    val slotRevision: Long
    /** Listener installed on the deck's player when it is first created. */
    val subListener: AudioEventListener

    /** The ready slot, else the pending slot. */
    fun nextQueuedLocked(): AudioTrack?
    /** Empties whichever slot holds exactly [track]. */
    fun consumeQueuedLocked(track: AudioTrack)
    /** Puts [track] back into the first free slot; false when both are taken. */
    fun restoreQueuedLocked(track: AudioTrack): Boolean
    fun matchesFenceLocked(fence: TransitionFence): Boolean
    /** [armed] is the main track that just reported its end, within the grace window. */
    fun mainEndedRecentlyLocked(armed: AudioTrack): Boolean
    /** Marks [track] as starting behind the handoff mask ramp; null clears it. */
    fun setMaskingLocked(track: AudioTrack?)
    fun resetTailCut()

    fun safePosition(track: AudioTrack): Long
    fun leadForLocked(track: AudioTrack): Long
    /** Takes [lock] itself. */
    fun seekPastLead(track: AudioTrack)
    fun incomingNormGainLocked(next: AudioTrack): Float
    fun setTransitionStatusLocked(kind: String, reason: String?)

    /** Takes both locks itself; call with neither held, or with only [playerMutationLock]. */
    fun fallbackAdvanceOrIdle()
    fun notifyMixerEvent(type: String, body: JsonObjectBuilder.() -> Unit)
    fun notifyAdvanced(handoffType: String, epoch: Long, seq: Long, encodedTrack: String?)
}

/**
 * One guild's overlap crossfade: prepares the incoming track on the [SubDeck],
 * drives the fade gains from the voice thread, and hands the incoming track
 * over to the main player.
 *
 * Deciding when to start a transition and the gapless advance stay with the
 * host. Lock order is unchanged: never take the host's `playerMutationLock`
 * while holding its `lock`.
 */
internal class CrossfadeEngine(
    private val host: CrossfadeHost,
    private val settings: MixerSettings,
    private val deck: SubDeck,
    private val protocol: ProtocolController,
    private val mainNorm: GainRamp,
    private val subNorm: GainRamp,
    private val playerManager: AudioPlayerManager,
) {
    /** Voice thread reads this without taking [lock]; races only shift a fade by a chunk. */
    @Volatile var xfadeActive: Boolean = false
        private set
    @Volatile var activeRecipe: TransitionRecipe? = null
        private set

    /** Test seam: run [recipe] over the given chain window without players. */
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
    val currentXfadeSample: Long get() = xfadeRecipeSampleAt(host.chainPosSamples)

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

    /** Fence of the overlap being prepared on the action worker, if any. */
    var overlapPreparation: TransitionFence? = null
        private set
    var xfadeHandoffMs: Long = -1L
        private set

    fun destroyLocked() {
        overlapPreparation = null
        xfadeActive = false
        activeRecipe = null
    }

    /** Main-track instance the armed overlap belongs to; a handoff must never fire after that track was replaced. */
    var xfadeMainTrack: AudioTrack? = null
        private set
    /** Position within [SubDeck.track] that has reached the audible output. */
    private val subOutputSamples = AtomicLong(0L)
    private val subSourceMilliSamples = AtomicLong(0L)
    /** Per-chunk incoming source position, so a handoff can bridge already-mixed output. */
    private val handoffSourceMap = HandoffSourceMap()
    @Volatile private var xfadeBeatMatched: Boolean = false
    @Volatile private var xfadeFallbackReason: String? = null
    private var subStartPositionMs: Long = 0L

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
    @Volatile var xfadeStartSample: Long = -1L
        private set
    @Volatile var xfadeEndSample: Long = -1L
        private set
    @Volatile private var xfadeFadeEndSample: Long = -1L

    /** Sets the main and sub gains for the chunk ending at [endOfChunk]; voice thread, lock-free. */
    fun applyFadeGains(endOfChunk: Long, samples: Int) {
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
    }

    fun takeSub(samples: Int, channels: Int, out: Array<FloatArray>): Int {
        if (!xfadeActive) return deck.siphon.take(samples, channels, out)
        val start = xfadeStartSample
        if (start < 0) {
            val read = deck.siphon.takeWithSource(samples, channels, out)
            subSourceMilliSamples.addAndGet(read.sourceMilliSamples)
            return read.outputSamples
        }
        val chunkStart = host.chainPosSamples - samples
        val desired = (chunkStart - start).coerceAtLeast(0L)
        val prefix = (start - chunkStart).coerceIn(0L, samples.toLong()).toInt()
        val read = deck.siphon.takeAt(desired, samples - prefix, channels, out)
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

    /** Counts only PCM that is actually mixed into the Discord output. */
    fun noteSubOutput(samples: Int) {
        if (samples <= 0 || !xfadeActive) return
        subOutputSamples.addAndGet(samples.toLong())
    }

    /**
     * Throttled DEBUG diagnostic: an overlap is fading in but the
     * secondary decoder did not supply the whole chunk, so the mix dropped
     * to silence on the sub side for this frame.
     */
    fun noteUnderrun(missing: Int, requested: Int) {
        if (!log.isDebugEnabled) return
        val prefix = if (xfadeStartSample >= 0L) {
            (xfadeStartSample - (host.chainPosSamples - requested)).coerceIn(0L, requested.toLong()).toInt()
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
            host.guildId, starved, requested - prefix,
            deck.siphon.pendingFrames, deck.siphon.pendingSamples * 1000L / SAMPLE_RATE,
            deck.siphon.droppedFrames, deck.siphon.droppedSamples,
            subOutputSamples.get() * 1000L / SAMPLE_RATE,
        )
    }

    /** Test seam: arm a fake overlap so [GuildMixer.advance]/[noteSubOutput] can be driven without players. */
    fun beginXfadeForTest(totalSamples: Int) {
        xfadeTotal = totalSamples.coerceAtLeast(1)
        xfadeLeft = xfadeTotal
        xfadeStartSample = -1L
        xfadeEndSample = -1L
        xfadeFadeEndSample = -1L
        subOutputSamples.set(0L)
        subSourceMilliSamples.set(0L)
        xfadeActive = true
    }

    fun endXfadeForTest() {
        xfadeActive = false
        activeRecipe = null
    }

    /** Test seam: fully reset overlap state without touching sub listeners. */
    fun clearXfadeForTest() {
        synchronized(host.lock) {
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
    fun armHandoffForTest(mainTrack: AudioTrack?, handoffMs: Long) {
        synchronized(host.lock) {
            xfadeActive = true
            xfadeHandoffMs = handoffMs
            xfadeMainTrack = mainTrack
        }
    }

    fun subOutputSamplesForTest(): Long = subOutputSamples.get()
    fun subSourceSamples(): Long = (subSourceMilliSamples.get() + 500L) / 1000L
    fun isXfadeBeatMatchedForTest(): Boolean = xfadeBeatMatched

    /** Test seam: pin the planned fade window as a running overlap would. */
    fun setXfadeWindowForTest(startSample: Long, endSample: Long) {
        xfadeStartSample = startSample
        xfadeEndSample = endSample
    }

    /** Detailed overlap progress, logged at most once per second at DEBUG. */
    fun logXfadeProgress(action: Any, filterRuns: Long) {
        val now = System.currentTimeMillis()
        if (now - xfadeLogMs < 1000) return
        xfadeLogMs = now
        val track = host.playingTrack ?: return
        val pos = host.safePosition(track)
        val chain = host.chainPosSamples * 1000L / SAMPLE_RATE
        val handoff: Long
        val subPos: Long
        val subOutMs: Long
        synchronized(host.lock) {
            handoff = xfadeHandoffMs
            subPos = deck.track?.let { host.safePosition(it) } ?: -1L
            subOutMs = subOutputSamples.get()
        }
        log.debug(
            "crossfade progress guild {} at {}ms (decoded {}ms) /handoff {}ms ({}ms left, action={}): " +
                "pending={}f/{}ms dropped={}f subOut={}ms subPos={}ms mixerFilterCalls={}",
            host.guildId, pos, chain, handoff, (handoff - pos).coerceAtLeast(0), action,
            deck.siphon.pendingFrames, deck.siphon.pendingSamples * 1000L / SAMPLE_RATE,
            deck.siphon.droppedFrames,
            subOutMs * 1000L / SAMPLE_RATE,
            subPos,
            filterRuns,
        )
    }

    fun refreshBeatmatchStatusLocked() {
        if (xfadeBeatMatched && deck.factory?.nativeFailed == true) {
            xfadeBeatMatched = false
            xfadeFallbackReason = "timescale_native_failed"
            host.setTransitionStatusLocked("crossfade", "timescale_native_failed")
        }
    }

    /** Milliseconds until an armed overlap hands off; null when no crossfade is running. */
    fun crossfadeHandoffInMs(): Long? = synchronized(host.lock) {
        if (!xfadeActive || xfadeHandoffMs < 0) return null
        val pos = host.playingTrack?.let { host.safePosition(it) } ?: 0L
        (xfadeHandoffMs - pos).coerceAtLeast(0)
    }

    fun prepareOverlap(plan: CrossfadePlan, fence: TransitionFence, analysisLog: String) {
        synchronized(host.lock) {
            if (overlapPreparation != null || host.overlayActive || xfadeActive || !host.matchesFenceLocked(fence)) return
            overlapPreparation = fence
        }
        protocol.executeAction {
            try {
                startOverlap(plan, fence, analysisLog)
            } catch (e: Exception) {
                log.warn("overlap preparation failed on guild {}", host.guildId, e)
            } finally {
                synchronized(host.lock) { if (overlapPreparation === fence) overlapPreparation = null }
            }
        }
    }

    private fun startOverlap(plan: CrossfadePlan, expected: TransitionFence, analysisLog: String) {
        data class OverlapSetup(val next: AudioTrack, val epoch: Long, val seq: Long, val revision: Long, val requestedStartMs: Long)
        data class StartedOverlap(val generation: Long, val startSample: Long, val beatMatched: Boolean, val fallbackReason: String?)
        // Keep the original queued until PCM is ready, so natural track end can still advance it.
        val (queued, epochAtStart, seqAtStart, revisionAtStart, requestedStartMs) = synchronized(host.lock) {
            if (host.destroyedLocked || !settings.crossfadeEnabled || protocol.preserveTransition) return
            if (!host.matchesFenceLocked(expected)) return
            val n = host.nextQueuedLocked() ?: return
            OverlapSetup(n, protocol.gate.lastKnownEpoch, protocol.advanceSeq.get(), host.slotRevision, protocol.armedStartPositionMs)
        }
        val next = queued.makeClone()
        if ((plan.beatMatched || plan.incomingStartMs > 0 || requestedStartMs > 0) && next.isSeekable) {
            val lead = if (settings.silenceSkipEnabled) synchronized(host.lock) { host.leadForLocked(next) } else 0L
            val target = maxOf(lead, plan.incomingStartMs, requestedStartMs)
            if (target > 0L) {
                try {
                    val duration = next.duration
                    next.position = if (duration > 0) target.coerceIn(0, (duration - 500).coerceAtLeast(0)) else target
                    log.debug("beatmatch seek to {}ms on guild {}", target, host.guildId)
                } catch (e: Exception) {
                    log.warn("beatmatch seek failed on guild {}", host.guildId, e)
                }
            }
        } else {
            host.seekPastLead(next)
        }
        val incomingStartPosition = host.safePosition(next)
        // playTrack starts decoding asynchronously. Wait for captured PCM on the
        // action worker, keeping the shared scheduler and main PCM path running.
        val sub = synchronized(host.lock) {
            if (host.destroyedLocked) return
            val s = deck.ensureLocked(host.subListener)
            deck.siphon.clear()
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
            log.warn("overlap pre-start play failed on guild {}", host.guildId, e)
        }
        val ready = deck.prefill(sub) {
            synchronized(host.lock) { settings.crossfadeEnabled && !protocol.preserveTransition && host.matchesFenceLocked(expected) && !host.overlayActive && !xfadeActive }
        }
        val started = synchronized(host.lock) {
            val startSample = maxOf(plan.startMs * SAMPLE_RATE / 1000, host.chainPosSamples)
            if (!ready || !settings.crossfadeEnabled || protocol.preserveTransition || host.overlayActive || xfadeActive ||
                startSample >= plan.handoffMs * SAMPLE_RATE / 1000 || !host.matchesFenceLocked(expected) ||
                protocol.gate.lastKnownEpoch != epochAtStart || protocol.advanceSeq.get() != seqAtStart ||
                host.slotRevision != revisionAtStart || protocol.playbackState != PlaybackState.ARMED) {
                if (!xfadeActive) deck.factory?.resetSpeed()
                null
            } else {
                host.consumeQueuedLocked(queued)
                val nativeFailed = deck.factory?.nativeFailed == true
                if (plan.beatMatched && nativeFailed) {
                    deck.factory?.resetSpeed()
                }
                xfadeBeatMatched = plan.beatMatched && !nativeFailed
                xfadeFallbackReason = if (plan.beatMatched && nativeFailed) "timescale_native_failed" else plan.fallbackReason

                deck.track = next
                // The started kind, not the planned one, is what overlapStart, overlapEnd,
                // and MixerAdvancedEvent report (notifyMixerEvent adds it to each).
                host.setTransitionStatusLocked(
                    if (nativeFailed) "crossfade" else plan.transitionKind,
                    if (nativeFailed) "timescale_native_failed" else plan.reason,
                )
                xfadeHandoffMs = plan.handoffMs
                xfadeMainTrack = host.playingTrack
                subOutputSamples.set(0L)
                subSourceMilliSamples.set(0L)
                handoffSourceMap.reset()
                subStartPositionMs = incomingStartPosition
                deck.startMs = System.currentTimeMillis()
                xfadeTotal = GuildMixer.msToSamples(plan.durationMs).coerceAtLeast(1)
                xfadeLeft = xfadeTotal
                xfadeStartSample = startSample
                xfadeEndSample = plan.handoffMs * SAMPLE_RATE / 1000
                xfadeFadeEndSample = maxOf(startSample + 1, (plan.startMs + plan.overlapMs) * SAMPLE_RATE / 1000)
                // Sub-track normalization gain: applied on top of the sin curve.
                activeRecipe = plan.recipe
                plan.recipe?.let { TransitionMemory.global.record(host.guildId, it.strategy) }
                subNorm.setNow(host.incomingNormGainLocked(next))
                if (startSample > plan.startMs * SAMPLE_RATE / 1000) {
                    log.debug(
                        "crossfade on guild {} armed {}ms after its window opened " +
                            "(chain at {}ms, window {}ms..{}ms); the fade starts late",
                        host.guildId, (startSample - plan.startMs * SAMPLE_RATE / 1000) * 1000L / SAMPLE_RATE,
                        host.chainPosSamples * 1000L / SAMPLE_RATE, plan.startMs, plan.handoffMs,
                    )
                }
                xfadeLogMs = 0L
                xfadeActive = true
                StartedOverlap(++deck.gen, startSample, xfadeBeatMatched, xfadeFallbackReason)
            }
        }
        if (started == null) {
            if (sub.playingTrack === next) {
                runCatching { sub.stopTrack() }.onFailure { log.warn("superseded overlap stop failed on guild {}", host.guildId, it) }
            }
            if (synchronized(host.lock) { !xfadeActive && !host.overlayActive }) deck.siphon.clear()
            return
        }
        // A control command (override/invalidate/reset/arm) that aborted the fade
        // while playTrack was in flight bumps deck.gen and stops an empty sub —
        // the playTrack above then decodes a zombie into a torn-down slot.
        // Detect and unwind it: stop the decoder and only restore if not superseded by control plane.
        val stillCurrent = synchronized(host.lock) { deck.gen == started.generation && xfadeActive && deck.track === next }
        if (!stillCurrent) {
            log.warn("overlap superseded during start on guild {}; unwinding '{}'", host.guildId, titleOf(next))
            if (sub.playingTrack === next) {
                try {
                    sub.stopTrack()
                } catch (e: Exception) {
                    log.warn("superseded overlap stop failed on guild {}", host.guildId, e)
                }
            }
            synchronized(host.lock) {
                if (!xfadeActive || deck.track === next) {
                    deck.factory?.resetSpeed()
                    xfadeBeatMatched = false
                    xfadeFallbackReason = null
                }
                // Only restore if the same arm epoch/seq is still active (e.g. transient sub contention),
                // never when superseded by invalidate/override/reset/re-arm.
                if (protocol.gate.lastKnownEpoch == epochAtStart && protocol.advanceSeq.get() == seqAtStart &&
                    host.slotRevision == revisionAtStart && protocol.playbackState == PlaybackState.ARMED) {
                    if (!host.restoreQueuedLocked(next)) {
                        log.warn("dropping superseded overlap on guild {}: slot busy", host.guildId)
                    }
                }
            }
            if (synchronized(host.lock) { !xfadeActive && !host.overlayActive }) deck.siphon.clear()
            return
        }
        val recipeStrategy = activeRecipe?.strategy?.name?.lowercase() ?: "crossfade"
        val beatmatchLog = if (started.beatMatched) {
            "strategy=$recipeStrategy targetBpm=${plan.targetBpm} rateRatio=${plan.rateRatio} fadeBeats=${plan.fadeBeats}"
        } else "strategy=$recipeStrategy rateRatio=1.0"
        log.info(
            "crossfade guild={} from='{}' to='{}' beatMatched={} reason={} durationMs={} lateMs={} {} {}",
            host.guildId, expected.outgoing?.let { titleOf(it) }?.replace('\n', ' ')?.replace('\r', ' '),
            titleOf(next).replace('\n', ' ').replace('\r', ' '), started.beatMatched,
            started.fallbackReason ?: plan.reason ?: "none",
            (plan.handoffMs * SAMPLE_RATE / 1000 - started.startSample) * 1000 / SAMPLE_RATE,
            (started.startSample - plan.startMs * SAMPLE_RATE / 1000) * 1000 / SAMPLE_RATE,
            beatmatchLog, analysisLog,
        )
        val mainPosMs = host.playingTrack?.let { host.safePosition(it) } ?: 0L
        host.notifyMixerEvent("overlapStart") {
            put("crossfadeMs", plan.durationMs)
            put("overlapMs", plan.overlapMs)
            put("recoveryMs", plan.recoveryMs)
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

    fun doHandoff() {
        val handoffAllowed = synchronized(host.lock) {
            val armed = xfadeMainTrack ?: return@synchronized true
            val playing = host.playingTrack
            // Lavaplayer nulls playingTrack before dispatching FINISHED/LOAD_FAILED,
            // so a handoff driven by onTrackEnd legitimately sees no playing track;
            // the host keeps that just-ended instance recognizable for a short
            // window. Anything else means the overlap was armed on a track that was
            // really replaced — never hand off into it.
            val endedRecently = host.mainEndedRecentlyLocked(armed)
            playing === armed || endedRecently
        }
        if (!handoffAllowed) {
            // Callers decide under the lock and act after releasing it, so a
            // client replace can slip in between; never hand off into a track
            // the overlap was not armed on. Re-evaluate the slot so a stuck
            // main track still advances instead of stalling silent.
            abortXfade("handoff arrived after the armed main track was replaced")
            host.fallbackAdvanceOrIdle()
            return
        }
        data class Plan(
            val handoff: Handoff?,
            val epochAtStart: Long,
            val seqAtStart: Long,
            val wasBeatMatched: Boolean,
            val fallbackReason: String?,
        )
        val plan = synchronized(host.lock) {
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
                deck.resetGainsLocked()
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
                        subReportedMs = host.safePosition(sub),
                        subOutMs = subOutMs,
                        droppedFrames = deck.siphon.droppedFrames,
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
            host.fallbackAdvanceOrIdle()
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
            log.warn("handoff seek failed on guild {}, starting from 0", host.guildId, e)
        }
        val sub = synchronized(host.lock) { deck.player }
        synchronized(host.playerMutationLock) {
            // Re-check under lock inside playerMutationLock: if an override/invalidate/reset
            // arrived while seeking or waiting for playerMutationLock, abort before touching
            // deck.mainGain or replacing the new main track.
            val committedSeq = synchronized(host.lock) {
                if (protocol.gate.lastKnownEpoch != plan.epochAtStart || protocol.advanceSeq.get() != plan.seqAtStart) {
                    if (deck.track === handoff.source) deck.track = null
                    deck.resetGainsLocked()
                    null
                } else {
                    if (deck.track === handoff.source) deck.track = null
                    host.setMaskingLocked(handoff.clone)
                    protocol.lastCountedTrack = handoff.clone
                    protocol.advanceSeq.incrementAndGet()
                }
            }
            if (committedSeq == null) {
                try {
                    sub?.stopTrack()
                } catch (e: Exception) {
                    log.warn("sub stop failed on guild {}", host.guildId, e)
                }
                deck.factory?.resetSpeed()
                deck.siphon.clear()
                log.warn("crossfade handoff superseded on guild {}; dropping '{}'", host.guildId, titleOf(handoff.source))
                return
            }
            try {
                sub?.stopTrack()
            } catch (e: Exception) {
                log.warn("sub stop failed on guild {}", host.guildId, e)
            }
            deck.factory?.resetSpeed()
            deck.siphon.clear()
            deck.subGain.setNow(0f)
            deck.mainGain.setNow(0f)
            deck.mainGain.rampTo(1f, GuildMixer.msToSamples(settings.maskMs).coerceAtLeast(1))
            // Handoff carries the sub track's normalization gain over to the
            // main slot so the incoming track keeps its level through the mask.
            mainNorm.copyFrom(subNorm)
            subNorm.setNow(1f)
            host.resetTailCut()
            val mainPlayer = host.mainPlayer
            if (mainPlayer == null) {
                synchronized(host.lock) {
                    host.setMaskingLocked(null)
                    deck.resetGainsLocked()
                }
                host.fallbackAdvanceOrIdle()
                return
            }
            try {
                val bridge = playHandoffClone(mainPlayer, handoff)
                log.debug(
                    "crossfade handoff on guild {} at {}ms (sub decoder was at {}ms, " +
                        "{}ms of sub audio heard, {} frames dropped; {})",
                    host.guildId, bridge.positionMs.takeIf { it >= 0 } ?: handoff.outputPosition,
                    handoff.subReportedMs, handoff.subOutMs, handoff.droppedFrames, bridge.describe(),
                )
                host.notifyMixerEvent("overlapEnd") {
                    put("reason", "handoff")
                    put("beatMatched", plan.wasBeatMatched)
                    if (!plan.wasBeatMatched && plan.fallbackReason != null) {
                        put("fallbackReason", plan.fallbackReason)
                    }
                    putTrack(handoff.source)
                }
                host.notifyAdvanced("CROSSFADE", plan.epochAtStart, committedSeq, handoff.encodedTrack)
            } catch (e: Exception) {
                log.warn("handoff play failed on guild {}", host.guildId, e)
                synchronized(host.lock) {
                    host.setMaskingLocked(null)
                    deck.resetGainsLocked()
                }
                host.fallbackAdvanceOrIdle()
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
                        host.guildId, startupMs, prefixLeftFrames * 20L,
                    )
                } else {
                    // The bridge ran dry before the clone produced audio: a gap was heard.
                    log.info(
                        "handoff clone on guild {} took {}ms to produce audio; the {}ms bridge left a ~{}ms gap",
                        host.guildId, startupMs, result.bridgeMs, (startupMs - result.bridgeMs).coerceAtLeast(0L),
                    )
                }
            }
        }
        HandoffBridge.withPrefix(builder) { mainPlayer.play(handoff.clone) }
        if (result.skipped == null) {
            log.debug(
                "handoff bridge on guild {}: {}ms of mixed audio covers the clone's start (built in {}us)",
                host.guildId, result.bridgeMs, (System.nanoTime() - startedAt) / 1000L,
            )
        }
        return result
    }

    fun abortXfade(reason: String) {
        var wasBeatMatched = false
        var fallbackReason: String? = null
        val (sub, armedSub, subName) = synchronized(host.lock) {
            // Idempotent: nothing armed means a duplicate/late abort (e.g. a
            // deliberate sub.stopTrack() racing the listener) must not replay
            // teardown, clobber a handoff mask ramp, or spam overlapEnd.
            if (!xfadeActive && deck.track == null) return@abortXfade
            refreshBeatmatchStatusLocked()
            wasBeatMatched = xfadeBeatMatched
            fallbackReason = xfadeFallbackReason
            xfadeBeatMatched = false
            xfadeFallbackReason = null
            val subPosMs = deck.track?.let { host.safePosition(it) } ?: -1L
            val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
            val elapsed = if (deck.startMs > 0) System.currentTimeMillis() - deck.startMs else -1L
            val name = deck.track?.let { titleOf(it) } ?: "-"
            log.warn(
                "crossfade aborted on guild {} after {}ms: {} " +
                    "(sub '{}' subPos={}ms subOut={}ms pending={}f dropped={}f)",
                host.guildId, elapsed, reason, name, subPosMs, subOutMs,
                deck.siphon.pendingFrames, deck.siphon.droppedFrames,
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
            deck.siphon.clear()
            deck.mainGain.rampTo(1f, GuildMixer.msToSamples(settings.duckFadeMs))
            deck.subGain.setNow(0f)
            Triple(deck.player, armed, name)
        }
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {} (was '{}')", host.guildId, subName, e)
        }
        deck.factory?.resetSpeed()
        host.notifyMixerEvent("overlapEnd") {
            put("reason", "aborted")
            put("detail", reason)
            put("beatMatched", wasBeatMatched)
            fallbackReason?.let { put("fallbackReason", it) }
            putTrack(armedSub)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CrossfadeEngine::class.java)
        private const val SAMPLE_RATE = 48000
    }
}
