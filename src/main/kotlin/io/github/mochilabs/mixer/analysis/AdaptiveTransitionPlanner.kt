package io.github.mochilabs.mixer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import org.slf4j.LoggerFactory

/**
 * Adaptive transition planner. Analyzes the outgoing and incoming track
 * boundaries and emits a declarative [TransitionRecipe]. The recipe is the
 * single source of truth for a transition; the crossfade window (phase-snapped
 * start/handoff/incoming start) is derived from it via [toCrossfadePlan].
 */
object AdaptiveTransitionPlanner {
    private val log = LoggerFactory.getLogger(AdaptiveTransitionPlanner::class.java)
    private const val SAMPLE_RATE = 48000

    fun plan(
        outgoingGridTail: ExtendedBeatGrid?,
        incomingGridHead: ExtendedBeatGrid?,
        outgoingFeaturesTail: TransitionFeatures?,
        incomingFeaturesHead: TransitionFeatures?,
        requestedCrossfadeMs: Long,
        contentEndMs: Long,
        incomingLeadMs: Long = 0L,
        incomingDurationMs: Long = -1L,
        maxTempoChange: Float = 0.06f,
        minConfidence: Float = 0.30f,
        preserveContext: Boolean = false,
        guildId: Long? = null,
        memory: TransitionMemory? = null,
    ): TransitionRecipe {
        if (preserveContext) {
            return TransitionRecipe(
                strategy = TransitionStrategy.ABSOLUTE_FALLBACK,
                totalSamples = 0L,
                sync = BeatSyncConfig(enabled = false, targetBpm = 0f),
                reason = "preserve_context",
            )
        }

        val outGrid = outgoingGridTail?.takeIf { it.bpmConfidence >= minConfidence && it.bpm.isFinite() && it.bpm > 0f }
        val inGrid = incomingGridHead?.takeIf { it.bpmConfidence >= minConfidence && it.bpm.isFinite() && it.bpm > 0f }

        // Level 0: Outgoing grid is valid
        if (outGrid != null) {
            if (inGrid != null) {
                // 1. Symmetrical octave-normalized log2 tempo comparison (1.0 -> 0.5 -> 2.0)
                val multipliers = listOf(1.0, 0.5, 2.0)
                val bestM = multipliers.minByOrNull { m -> abs(log2((outGrid.bpm * m) / inGrid.bpm)) } ?: 1.0
                val deltaLog = abs(log2((outGrid.bpm * bestM) / inGrid.bpm))

                // 2. Dynamic uncertainty deadband, threshold from the configured tempo-change bound.
                val baseThreshold = log2(1.0 + maxTempoChange.coerceIn(0f, 0.12f))
                val minBpmConf = min(outGrid.bpmConfidence, inGrid.bpmConfidence)
                val margin = max(0.003, 0.015 * (1.0 - minBpmConf))
                val thresholdLow = baseThreshold - margin
                val thresholdHigh = baseThreshold + margin

                val isSync = if (deltaLog <= thresholdLow) {
                    true
                } else if (deltaLog > thresholdHigh) {
                    false
                } else {
                    // Inside deadband: verify energy & rhythm compatibility
                    val rmsOut = outgoingFeaturesTail?.rms ?: 0f
                    val rmsIn = incomingFeaturesHead?.rms ?: 0f
                    val safeEnergy = if (min(rmsOut, rmsIn) > 0f) (max(rmsOut, rmsIn) / min(rmsOut, rmsIn)) <= 3.16f else true
                    val safeRhythm = min(outgoingFeaturesTail?.rhythmicStability ?: 1f, incomingFeaturesHead?.rhythmicStability ?: 1f) >= 0.45f
                    val safeKey = !keyClash(outgoingFeaturesTail?.key, incomingFeaturesHead?.key)
                    safeEnergy && safeRhythm && safeKey
                }

                log.debug(
                    "planner level0 guild={} bpmOut={} bpmIn={} bestM={} deltaLog={} deadband={}..{} isSync={}",
                    guildId, outGrid.bpm, inGrid.bpm, bestM, deltaLog, thresholdLow, thresholdHigh, isSync,
                )

                if (isSync) {
                    val rateRatio = ((outGrid.bpm * bestM) / inGrid.bpm).toFloat()
                    val hasDownbeats = outGrid.downbeatConfidence >= 0.5f && inGrid.downbeatConfidence >= 0.5f &&
                        !outGrid.isNon44Meter && !inGrid.isNon44Meter
                    val periodMs = 60_000.0 / outGrid.bpm.toDouble()
                    val periodSamples = (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())
                    val recoveryMs = if (abs(rateRatio - 1f) < 0.0001f) 0L else {
                        val desired = maxOf(500.0, abs(rateRatio - 1f) * 20_000.0)
                        round(ceil(desired / periodMs) * periodMs).toLong()
                    }
                    val recoverySamples = recoveryMs * SAMPLE_RATE / 1000L

                    if (hasDownbeats) {
                        val hasPhrases = outGrid.phraseConfidence >= 0.5f && inGrid.phraseConfidence >= 0.5f
                        val candidates = if (hasPhrases) {
                            listOf(
                                TransitionStrategy.PHRASE_MATCHED_BASS_SWAP to 1.0f,
                                TransitionStrategy.SHORT_BASS_SWAP to 0.85f,
                                TransitionStrategy.BEATMATCHED_BLEND to 0.70f,
                            )
                        } else {
                            listOf(
                                TransitionStrategy.SHORT_BASS_SWAP to 1.0f,
                                TransitionStrategy.BEATMATCHED_BLEND to 0.80f,
                            )
                        }
                        val chosen = selectWithMemory(candidates, guildId, memory)
                        if (chosen != TransitionStrategy.BEATMATCHED_BLEND) {
                            val maxFadeMs = (requestedCrossfadeMs - recoveryMs).coerceAtLeast(0L)
                            val beatOptions = if (chosen == TransitionStrategy.PHRASE_MATCHED_BASS_SWAP) listOf(16, 8, 4, 2) else listOf(8, 4, 2)
                            val fadeBeats = beatOptions.firstOrNull { b ->
                                round(b * periodMs).toLong() <= maxFadeMs
                            } ?: 4
                            val cutBar = (fadeBeats / 4).coerceAtLeast(1)
                            val fadeMs = round(fadeBeats * periodMs).toLong()
                            val fadeSamples = Math.round(fadeBeats * periodSamples)
                            val window = syncWindow(outGrid, inGrid, fadeMs, recoveryMs, contentEndMs, incomingLeadMs, incomingDurationMs, rateRatio)
                                ?: return beatmatchBlendOrFallback(outGrid, inGrid, requestedCrossfadeMs, contentEndMs, incomingLeadMs, incomingDurationMs, rateRatio)
                            val totalSamples = fadeSamples + recoverySamples
                            val swapSample = Math.round(cutBar * 4 * periodSamples)

                            return TransitionRecipe(
                                strategy = chosen,
                                totalSamples = totalSamples,
                                sync = BeatSyncConfig(
                                    enabled = true,
                                    targetBpm = outGrid.bpm,
                                    rateRatio = rateRatio,
                                    startSample = window.startSample,
                                    handoffSample = window.handoffSample,
                                    incomingStartSample = window.incomingStartSample,
                                    recoverySamples = recoverySamples,
                                ),
                                automations = listOf(
     ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, fadeSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                                    ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, fadeSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
                                    ParamAutomation.BassCut(TrackTarget.INCOMING, 0L, muteLow = true),
                                    ParamAutomation.BassCut(TrackTarget.INCOMING, swapSample, muteLow = false),
                                    ParamAutomation.BassCut(TrackTarget.OUTGOING, swapSample, muteLow = true),
                                ),
                            )
                        }
                    }

                    // Beatmatched Blend: volume only
                    val maxFadeMs = (requestedCrossfadeMs - recoveryMs).coerceAtLeast(0L)
                    val fadeBeats = listOf(8, 4, 2).firstOrNull { b ->
                        round(b * periodMs).toLong() <= maxFadeMs
                    } ?: 4
                    val fadeMs = round(fadeBeats * periodMs).toLong()
                    val fadeSamples = Math.round(fadeBeats * periodSamples)
                    val window = syncWindow(outGrid, inGrid, fadeMs, recoveryMs, contentEndMs, incomingLeadMs, incomingDurationMs, rateRatio) ?: return nonSyncWindowRecipe(TransitionStrategy.BEATMATCHED_BLEND, requestedCrossfadeMs, contentEndMs, rateRatio, recoverySamples)
                    val totalSamples = fadeSamples + recoverySamples
                    return TransitionRecipe(
                        strategy = TransitionStrategy.BEATMATCHED_BLEND,
                        totalSamples = totalSamples,
                        sync = BeatSyncConfig(
                            enabled = true,
                            targetBpm = outGrid.bpm,
                            rateRatio = rateRatio,
                            startSample = window.startSample,
                            handoffSample = window.handoffSample,
                            incomingStartSample = window.incomingStartSample,
                            recoverySamples = recoverySamples,
                        ),
                        automations = listOf(
                            ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, fadeSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                            ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, fadeSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
                        ),
                    )
                }

                // Async domain: Downbeat Cut check (weighted against Echo Out via TransitionMemory)
                if (outGrid.downbeatConfidence >= 0.6f && inGrid.downbeatConfidence >= 0.6f) {
                    val highEnergy = (outgoingFeaturesTail?.rms ?: 0f) > 0.1f && (incomingFeaturesHead?.rms ?: 0f) > 0.1f
                    if (highEnergy) {
                        val chosenAsync = selectWithMemory(
                            listOf(
                                TransitionStrategy.DOWNBEAT_CUT to 1.0f,
                                TransitionStrategy.ECHO_OUT to 0.85f,
                            ),
                            guildId,
                            memory,
                        )
                        if (chosenAsync == TransitionStrategy.DOWNBEAT_CUT) {
                            val totalSamples = 480L // 10ms quick cut
                            return nonSyncWindowRecipe(TransitionStrategy.DOWNBEAT_CUT, totalSamples, contentEndMs, 1.0f, 0L, reason = "tempo_diff_exceeded")
                        }
                    }
                }
            }

            // Async fallback or incoming grid missing: Echo Out on outgoing tempo
            val delayBeats = if (outGrid.bpm >= 125f) 0.5f else 1.0f
            val delaySamples = Math.round(delayBeats * (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())).toInt().coerceIn(1, 95999)
            val totalSamples = 96000L // 2.0s
            return TransitionRecipe(
                strategy = TransitionStrategy.ECHO_OUT,
                totalSamples = totalSamples,
                sync = BeatSyncConfig(
                    enabled = false,
                    targetBpm = outGrid.bpm,
                    startSample = (contentEndMs - totalSamples * 1000L / SAMPLE_RATE).coerceAtLeast(0L) * SAMPLE_RATE / 1000L,
                    handoffSample = contentEndMs * SAMPLE_RATE / 1000L,
                ),
                automations = listOf(
                    ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, 0L, 0.0f, 0.0f),
                    ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, 0L, 1.0f, 1.0f),
                    ParamAutomation.EchoTrigger(
                        atSample = 0L,
                        delaySamples = delaySamples,
                        feedback = 0.65f,
                        terminationRampSamples = 86400, // 1.8s
                    ),
                ),
                reason = if (inGrid != null) "tempo_diff_exceeded" else "missing_incoming_grid",
            )
        }

        // Level 1: BeatGrid failed, but waveform features are valid
        if (outgoingFeaturesTail != null && incomingFeaturesHead != null &&
            outgoingFeaturesTail.unknownReason == null && incomingFeaturesHead.unknownReason == null) {
            val totalSamples = minOf(requestedCrossfadeMs, 4000L) * SAMPLE_RATE / 1000L
            return TransitionRecipe(
                strategy = TransitionStrategy.DEGRADED_FILTER_SWEEP,
                totalSamples = totalSamples,
                sync = BeatSyncConfig(
                    enabled = false,
                    targetBpm = 0f,
                    startSample = (contentEndMs - totalSamples * 1000L / SAMPLE_RATE).coerceAtLeast(0L) * SAMPLE_RATE / 1000L,
                    handoffSample = contentEndMs * SAMPLE_RATE / 1000L,
                ),
                automations = listOf(
                    ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, totalSamples, 1.0f, 0.0f, RampCurve.LINEAR),
                    ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, totalSamples, 0.0f, 1.0f, RampCurve.LINEAR),
                    ParamAutomation.FilterCutoff(TrackTarget.OUTGOING, FilterType.HIGH_PASS, 0L, totalSamples, 20f, 3000f),
                ),
            )
        }

        // Level 2: Absolute safe fallback
        val totalSamples = 5000L * SAMPLE_RATE / 1000L
        return TransitionRecipe(
            strategy = TransitionStrategy.ABSOLUTE_FALLBACK,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(
                enabled = false,
                targetBpm = 0f,
                startSample = (contentEndMs - totalSamples * 1000L / SAMPLE_RATE).coerceAtLeast(0L) * SAMPLE_RATE / 1000L,
                handoffSample = contentEndMs * SAMPLE_RATE / 1000L,
            ),
            automations = listOf(
                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, totalSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, totalSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
            ),
            reason = "missing_all_metadata",
        )
    }

    private fun selectWithMemory(
        candidates: List<Pair<TransitionStrategy, Float>>,
        guildId: Long?,
        memory: TransitionMemory?,
    ): TransitionStrategy {
        val primary = candidates.first().first
        if (guildId == null || memory == null || candidates.size <= 1) return primary
        val chosen = candidates.maxByOrNull { (strategy, baseScore) ->
            baseScore * memory.repetitionWeight(guildId, strategy)
        }?.first ?: primary
        if (chosen != primary) {
            log.debug("planner memory variation guild={}: {} penalized -> selected {}", guildId, primary, chosen)
        }
        return chosen
    }

    /** Phase-snapped sync window in sample coordinates; null when the incoming track is too short. */
    private fun syncWindow(
        outgoing: ExtendedBeatGrid,
        incoming: ExtendedBeatGrid,
        fadeMs: Long,
        recoveryMs: Long,
        contentEndMs: Long,
        incomingLeadMs: Long,
        incomingDurationMs: Long,
        rateRatio: Float,
    ): SyncWindow? {
        val outPeriod = outgoing.periodMs
        val k = floor((contentEndMs - outgoing.phaseMs) / outPeriod).toLong()
        val snappedHandoffMs = (outgoing.phaseMs + round(k * outPeriod).toLong()).coerceIn(fadeMs + recoveryMs, contentEndMs)
        val startMs = (snappedHandoffMs - fadeMs - recoveryMs).coerceAtLeast(0L)
        val handoffMs = startMs + fadeMs + recoveryMs

        val leadMs = incomingLeadMs.coerceAtLeast(0L)
        val inPeriod = incoming.periodMs
        val m = if (leadMs <= incoming.phaseMs) 0L else ceil((leadMs - incoming.phaseMs) / inPeriod).toLong()
        val rawIncomingStartMs = (incoming.phaseMs + round(m * inPeriod).toLong()).coerceAtLeast(0L)
        if (incomingDurationMs > 0 && rawIncomingStartMs + fadeMs * rateRatio + recoveryMs * (rateRatio + 1f) / 2 >= incomingDurationMs - 500) {
            return null
        }
        return SyncWindow(
            startSample = startMs * SAMPLE_RATE / 1000L,
            handoffSample = handoffMs * SAMPLE_RATE / 1000L,
            incomingStartSample = rawIncomingStartMs * SAMPLE_RATE / 1000L,
        )
    }

    private data class SyncWindow(val startSample: Long, val handoffSample: Long, val incomingStartSample: Long)

    private fun beatmatchBlendOrFallback(
        outGrid: ExtendedBeatGrid,
        inGrid: ExtendedBeatGrid,
        requestedCrossfadeMs: Long,
        contentEndMs: Long,
        incomingLeadMs: Long,
        incomingDurationMs: Long,
        rateRatio: Float,
    ): TransitionRecipe {
        val recoveryMs = if (abs(rateRatio - 1f) < 0.0001f) 0L else {
            val periodMs = 60_000.0 / outGrid.bpm.toDouble()
            val desired = maxOf(500.0, abs(rateRatio - 1f) * 20_000.0)
            round(ceil(desired / periodMs) * periodMs).toLong()
        }
        return nonSyncWindowRecipe(TransitionStrategy.BEATMATCHED_BLEND, requestedCrossfadeMs, contentEndMs, rateRatio, recoveryMs * SAMPLE_RATE / 1000L)
    }

    private fun nonSyncWindowRecipe(
        strategy: TransitionStrategy,
        totalSamples: Long,
        contentEndMs: Long,
        rateRatio: Float,
        recoverySamples: Long,
        reason: String = strategy.name.lowercase(),
    ): TransitionRecipe {
        val fadeSamples = (totalSamples - recoverySamples).coerceAtLeast(0L)
        val startSample = (contentEndMs - totalSamples * 1000L / SAMPLE_RATE).coerceAtLeast(0L) * SAMPLE_RATE / 1000L
        return TransitionRecipe(
            strategy = strategy,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(
                enabled = strategy == TransitionStrategy.BEATMATCHED_BLEND,
                targetBpm = 0f,
                rateRatio = rateRatio,
                startSample = startSample,
                handoffSample = contentEndMs * SAMPLE_RATE / 1000L,
                recoverySamples = recoverySamples,
            ),
            automations = listOf(
                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, fadeSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, fadeSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
            ),
            reason = reason,
        )
    }

    private fun keyClash(a: KeyEstimate?, b: KeyEstimate?): Boolean {
        if (a == null || b == null || a !is KeyEstimate.Candidate || b !is KeyEstimate.Candidate || minOf(a.confidence, b.confidence) < 0.6f) return false
        val relA = (a.pitchClass + if (a.mode == KeyMode.MINOR) 3 else 0) % 12
        val relB = (b.pitchClass + if (b.mode == KeyMode.MINOR) 3 else 0) % 12
        val distance = abs(relA - relB)
        return distance !in setOf(0, 5, 7)
    }

    /** Maps a strategy to the wire `transitionKind` vocabulary preserved from protocol v2. */
    fun legacyKind(strategy: TransitionStrategy): String = when (strategy) {
        TransitionStrategy.PHRASE_MATCHED_BASS_SWAP,
        TransitionStrategy.SHORT_BASS_SWAP,
        TransitionStrategy.BEATMATCHED_BLEND -> "beat_matched"
        TransitionStrategy.ABSOLUTE_FALLBACK -> "short_crossfade"
        TransitionStrategy.ECHO_OUT,
        TransitionStrategy.DOWNBEAT_CUT,
        TransitionStrategy.DEGRADED_FILTER_SWEEP -> "crossfade"
    }

    /** Derives the crossfade window from a recipe. [contentEndMs] is the audible content end in track milliseconds. */
    fun toCrossfadePlan(recipe: TransitionRecipe, contentEndMs: Long): CrossfadePlan? {
        if (recipe.strategy == TransitionStrategy.ABSOLUTE_FALLBACK && recipe.totalSamples == 0L) {
            return null
        }
        val durationMs = (recipe.totalSamples * 1000L / SAMPLE_RATE).coerceAtLeast(1L)
        val recoveryMs = recipe.sync.recoverySamples * 1000L / SAMPLE_RATE
        val overlapMs = (durationMs - recoveryMs).coerceAtLeast(1L)
        val startMs = recipe.sync.startSample * 1000L / SAMPLE_RATE
        val handoffMs = recipe.sync.handoffSample * 1000L / SAMPLE_RATE
        val incomingStartMs = recipe.sync.incomingStartSample * 1000L / SAMPLE_RATE
        val beatMatched = recipe.sync.enabled
        val targetBpm = recipe.sync.targetBpm
        val fadeBeats = if (beatMatched && targetBpm > 0f) {
            Math.round(overlapMs.toDouble() / (60_000.0 / targetBpm.toDouble())).toInt()
        } else 0
        return CrossfadePlan(
            startMs = startMs,
            handoffMs = handoffMs,
            durationMs = durationMs,
            beatMatched = beatMatched,
            rateRatio = recipe.sync.rateRatio,
            incomingStartMs = incomingStartMs,
            fadeBeats = fadeBeats,
            targetBpm = targetBpm,
            fallbackReason = recipe.reason,
            overlapMs = overlapMs,
            recoveryMs = recoveryMs,
            transitionKind = legacyKind(recipe.strategy),
            reason = recipe.reason,
        )
    }
}
