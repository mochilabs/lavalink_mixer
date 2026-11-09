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

    // Strategy gates. Uncalibrated heuristics (see docs/adaptive-calibration.md),
    // tuned toward choosing beat-matched and bass-swap transitions more often.
    /** Minimum downbeat confidence on both grids for bass-swap strategies. */
    internal const val DOWNBEAT_GATE = 0.40f
    /** Minimum phrase confidence on both grids for the phrase-matched bass swap. */
    internal const val PHRASE_GATE = 0.40f
    /** Minimum downbeat confidence on both grids for a downbeat cut. */
    internal const val DOWNBEAT_CUT_GATE = 0.50f
    /** Minimum RMS on both sides for a downbeat cut. */
    internal const val DOWNBEAT_CUT_MIN_RMS = 0.08f
    /** Largest energy ratio (about 12 dB) still accepted inside the tempo deadband. */
    internal const val DEADBAND_MAX_ENERGY_RATIO = 3.98f
    /** Minimum rhythmic stability on both sides inside the tempo deadband. */
    internal const val DEADBAND_MIN_RHYTHM = 0.35f

    fun plan(
        outgoingGridTail: ExtendedBeatGrid?,
        incomingGridHead: ExtendedBeatGrid?,
        outgoingFeaturesTail: TransitionFeatures?,
        incomingFeaturesHead: TransitionFeatures?,
        requestedCrossfadeMs: Long,
        contentEndMs: Long,
        incomingLeadMs: Long = 0L,
        incomingDurationMs: Long = -1L,
        maxTempoChange: Float = 0.08f,
        minConfidence: Float = 0.30f,
        preserveContext: Boolean = false,
        guildId: Long? = null,
        memory: TransitionMemory? = null,
        trackPairSeed: Int = 0,
    ): TransitionRecipe {
        if (preserveContext) {
            return TransitionRecipe(
                strategy = TransitionStrategy.ABSOLUTE_FALLBACK,
                totalSamples = 0L,
                sync = BeatSyncConfig(enabled = false, targetBpm = 0f),
                reason = "preserve_context",
            )
        }

        if (requestedCrossfadeMs <= 0L || contentEndMs <= 0L) {
            return TransitionRecipe(
                strategy = TransitionStrategy.ABSOLUTE_FALLBACK,
                totalSamples = 0L,
                sync = BeatSyncConfig(enabled = false, targetBpm = 0f),
                reason = "crossfade_unavailable",
            )
        }

        val outGrid = outgoingGridTail?.takeIf { it.bpmConfidence >= minConfidence && it.bpm.isFinite() && it.bpm > 0f }
        val inGrid = incomingGridHead?.takeIf { it.bpmConfidence >= minConfidence && it.bpm.isFinite() && it.bpm > 0f }

        // Level 0: Outgoing grid is valid
        if (outGrid != null) {
            if (inGrid != null) {
                // 1. Symmetrical octave-normalized log2 tempo comparison (1.0 -> 0.5 -> 2.0)
                val multipliers = listOf(1.0, 0.5, 2.0)
                val octaveMultiplier = multipliers.minByOrNull { m -> abs(log2((outGrid.bpm * m) / inGrid.bpm)) } ?: 1.0
                val deltaLog = abs(log2((outGrid.bpm * octaveMultiplier) / inGrid.bpm))
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
                    val safeEnergy = if (min(rmsOut, rmsIn) > 0f) (max(rmsOut, rmsIn) / min(rmsOut, rmsIn)) <= DEADBAND_MAX_ENERGY_RATIO else true
                    val safeRhythm = min(outgoingFeaturesTail?.rhythmicStability ?: 1f, incomingFeaturesHead?.rhythmicStability ?: 1f) >= DEADBAND_MIN_RHYTHM
                    val safeKey = !keyClash(outgoingFeaturesTail?.key, incomingFeaturesHead?.key)
                    safeEnergy && safeRhythm && safeKey
                }

                log.debug(
                    "planner level0 guild={} bpmOut={} bpmIn={} octaveMultiplier={} deltaLog={} deadband={}..{} isSync={}",
                    guildId, outGrid.bpm, inGrid.bpm, octaveMultiplier, deltaLog, thresholdLow, thresholdHigh, isSync,
                )

                if (isSync) {
                    val rateRatio = ((outGrid.bpm * octaveMultiplier) / inGrid.bpm).toFloat()
                    val hasDownbeats = outGrid.downbeatConfidence >= DOWNBEAT_GATE && inGrid.downbeatConfidence >= DOWNBEAT_GATE &&
                        !outGrid.isNon44Meter && !inGrid.isNon44Meter
                    val periodMs = 60_000.0 / outGrid.bpm.toDouble()
                    val periodSamples = (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())
                    val recoveryMs = if (abs(rateRatio - 1f) < 0.0001f) 0L else {
                        val desired = maxOf(500.0, abs(rateRatio - 1f) * 20_000.0)
                        round(ceil(desired / periodMs) * periodMs).toLong()
                    }
                    val recoverySamples = recoveryMs * SAMPLE_RATE / 1000L

                    val chosen = if (hasDownbeats) {
                        val hasPhrases = outGrid.phraseConfidence >= PHRASE_GATE && inGrid.phraseConfidence >= PHRASE_GATE
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
                        selectWithMemory(candidates, guildId, memory, trackPairSeed)
                    } else {
                        TransitionStrategy.BEATMATCHED_BLEND
                    }

                    return buildSyncRecipe(
                        strategy = chosen,
                        outGrid = outGrid,
                        inGrid = inGrid,
                        periodMs = periodMs,
                        periodSamples = periodSamples,
                        requestedCrossfadeMs = requestedCrossfadeMs,
                        contentEndMs = contentEndMs,
                        incomingLeadMs = incomingLeadMs,
                        incomingDurationMs = incomingDurationMs,
                        rateRatio = rateRatio,
                        recoveryMs = recoveryMs,
                        recoverySamples = recoverySamples,
                    )
                }

                // Async domain: Downbeat Cut check (weighted against Echo Out via TransitionMemory)
                if (outGrid.downbeatConfidence >= DOWNBEAT_CUT_GATE && inGrid.downbeatConfidence >= DOWNBEAT_CUT_GATE) {
                    val highEnergy = (outgoingFeaturesTail?.rms ?: 0f) > DOWNBEAT_CUT_MIN_RMS && (incomingFeaturesHead?.rms ?: 0f) > DOWNBEAT_CUT_MIN_RMS
                    if (highEnergy) {
                        val chosenAsync = selectWithMemory(
                            listOf(
                                TransitionStrategy.DOWNBEAT_CUT to 1.0f,
                                TransitionStrategy.ECHO_OUT to 0.85f,
                            ),
                            guildId,
                            memory,
                            trackPairSeed,
                        )
                        if (chosenAsync == TransitionStrategy.DOWNBEAT_CUT) {
                            val totalSamples = 480L // 10ms quick cut
                            val outBarMs = outGrid.periodMs * 4.0
                            val outgoingBarIndex = floor((contentEndMs - outGrid.downbeatMs) / outBarMs).toLong()
                            val snappedCutMs = (outGrid.downbeatMs + round(outgoingBarIndex * outBarMs).toLong()).coerceIn(10L, contentEndMs)
                            val inBarMs = inGrid.periodMs * 4.0
                            val leadMs = incomingLeadMs.coerceAtLeast(0L)
                            val incomingBarIndex = if (leadMs <= inGrid.downbeatMs) 0L else ceil((leadMs - inGrid.downbeatMs) / inBarMs).toLong()
                            val inDownbeatMs = (inGrid.downbeatMs + round(incomingBarIndex * inBarMs).toLong()).coerceAtLeast(0L)
                            log.debug(
                                "planner async downbeat_cut guild={} cutMs={} inStartMs={}",
                                guildId, snappedCutMs, inDownbeatMs,
                            )
                            return nonSyncWindowRecipe(
                                TransitionStrategy.DOWNBEAT_CUT,
                                totalSamples,
                                snappedCutMs,
                                1.0f,
                                0L,
                                incomingStartSample = inDownbeatMs * SAMPLE_RATE / 1000L,
                                reason = "tempo_diff_exceeded",
                            )
                        }
                    }
                }
            }

            // Async fallback or incoming grid missing: Echo Out on outgoing tempo
            val delayBeats = if (outGrid.bpm >= 125f) 0.5f else 1.0f
            val totalSamples = minOf(96000L, contentEndMs * SAMPLE_RATE / 1000L).coerceAtLeast(2L) // up to 2.0s
            val delaySamples = Math.round(delayBeats * (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())).toInt()
                .coerceIn(1, (totalSamples - 1).toInt())
            log.debug(
                "planner async echo_out guild={} bpmOut={} delaySamples={}",
                guildId, outGrid.bpm, delaySamples,
            )
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
                        terminationRampSamples = (totalSamples * 9 / 10).toInt().coerceAtLeast(1), // 1.8s at full length
                    ),
                ),
                reason = when {
                    inGrid != null -> "tempo_diff_exceeded"
                    incomingGridHead != null -> "low_confidence_incoming_grid"
                    else -> "missing_incoming_grid"
                },
            )
        }

        // Level 1: BeatGrid failed, but waveform features are valid
        if (outgoingFeaturesTail != null && incomingFeaturesHead != null &&
            outgoingFeaturesTail.unknownReason == null && incomingFeaturesHead.unknownReason == null) {
            val totalSamples = minOf(requestedCrossfadeMs, 4000L, contentEndMs) * SAMPLE_RATE / 1000L
            log.debug("planner level1 degraded_filter_sweep guild={} totalSamples={}", guildId, totalSamples)
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
        val totalSamples = minOf(requestedCrossfadeMs, contentEndMs) * SAMPLE_RATE / 1000L
        log.debug("planner level2 absolute_fallback guild={} totalSamples={}", guildId, totalSamples)
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

    private fun buildSyncRecipe(
        strategy: TransitionStrategy,
        outGrid: ExtendedBeatGrid,
        inGrid: ExtendedBeatGrid,
        periodMs: Double,
        periodSamples: Double,
        requestedCrossfadeMs: Long,
        contentEndMs: Long,
        incomingLeadMs: Long,
        incomingDurationMs: Long,
        rateRatio: Float,
        recoveryMs: Long,
        recoverySamples: Long,
    ): TransitionRecipe {
        val isBassSwap = strategy == TransitionStrategy.PHRASE_MATCHED_BASS_SWAP || strategy == TransitionStrategy.SHORT_BASS_SWAP
        val maxFadeMs = (requestedCrossfadeMs - recoveryMs).coerceAtLeast(0L)
        // Longest bar-multiple fade that fits the requested window.
        val beatOptions = when (strategy) {
            TransitionStrategy.PHRASE_MATCHED_BASS_SWAP -> listOf(32, 16, 8, 4, 2)
            TransitionStrategy.SHORT_BASS_SWAP -> listOf(8, 4, 2)
            else -> listOf(16, 8, 4, 2)
        }
        val fadeBeats = beatOptions.firstOrNull { b -> round(b * periodMs).toLong() <= maxFadeMs }
            ?: return nonSyncWindowRecipe(
                TransitionStrategy.ABSOLUTE_FALLBACK,
                minOf(requestedCrossfadeMs, contentEndMs) * SAMPLE_RATE / 1000L,
                contentEndMs, 1.0f, 0L,
                reason = "fade_window_too_short",
            )
        val fadeMs = round(fadeBeats * periodMs).toLong()
        val fadeSamples = Math.round(fadeBeats * periodSamples)
        val window = syncWindow(outGrid, inGrid, fadeMs, recoveryMs, contentEndMs, incomingLeadMs, incomingDurationMs, rateRatio, snapToDownbeat = isBassSwap)
            ?: return if (isBassSwap) {
                beatmatchBlendOrFallback(outGrid, inGrid, requestedCrossfadeMs, contentEndMs, incomingLeadMs, incomingDurationMs, rateRatio)
            } else {
                nonSyncWindowRecipe(
                    TransitionStrategy.BEATMATCHED_BLEND, requestedCrossfadeMs * SAMPLE_RATE / 1000L, contentEndMs, rateRatio,
                    recoverySamples.coerceAtMost(requestedCrossfadeMs * SAMPLE_RATE / 2000L),
                )
            }
        val totalSamples = fadeSamples + recoverySamples
        val automations = buildList {
            add(ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, fadeSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER))
            add(ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, fadeSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER))
            if (isBassSwap) {
                // Swap bass at the fade midpoint (a bar line for 8/16-beat fades) so both
                // tracks overlap with the incoming low end muted, then trade places.
                val swapSample = Math.round(fadeBeats / 2 * periodSamples)
                add(ParamAutomation.BassCut(TrackTarget.INCOMING, 0L, muteLow = true))
                add(ParamAutomation.BassCut(TrackTarget.INCOMING, swapSample, muteLow = false))
                add(ParamAutomation.BassCut(TrackTarget.OUTGOING, swapSample, muteLow = true))
            }
        }
        return TransitionRecipe(
            strategy = strategy,
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
            automations = automations,
        )
    }

    private fun selectWithMemory(
        candidates: List<Pair<TransitionStrategy, Float>>,
        guildId: Long?,
        memory: TransitionMemory?,
        trackPairSeed: Int = 0,
    ): TransitionStrategy {
        val primary = candidates.first().first
        if (guildId == null || memory == null || candidates.size <= 1) return primary
        val chosen = memory.rank(guildId, candidates, trackPairSeed)
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
        snapToDownbeat: Boolean = false,
    ): SyncWindow? {
        val outStep = if (snapToDownbeat) outgoing.periodMs * 4.0 else outgoing.periodMs
        val outAnchor = if (snapToDownbeat) outgoing.downbeatMs else outgoing.phaseMs
        val k = floor((contentEndMs - outAnchor) / outStep).toLong()
        val candidateHandoffMs = outAnchor + round(k * outStep).toLong()
        val snappedHandoffMs = if (candidateHandoffMs >= fadeMs + recoveryMs) {
            candidateHandoffMs.coerceIn(fadeMs + recoveryMs, contentEndMs)
        } else {
            val kBeat = floor((contentEndMs - outgoing.phaseMs) / outgoing.periodMs).toLong()
            (outgoing.phaseMs + round(kBeat * outgoing.periodMs).toLong()).coerceIn(fadeMs + recoveryMs, contentEndMs)
        }
        val startMs = (snappedHandoffMs - fadeMs - recoveryMs).coerceAtLeast(0L)
        val handoffMs = startMs + fadeMs + recoveryMs

        val leadMs = incomingLeadMs.coerceAtLeast(0L)
        val inStep = if (snapToDownbeat) incoming.periodMs * 4.0 else incoming.periodMs
        val inAnchor = if (snapToDownbeat) incoming.downbeatMs else incoming.phaseMs
        val m = if (leadMs <= inAnchor) 0L else ceil((leadMs - inAnchor) / inStep).toLong()
        val rawIncomingStartMs = (inAnchor + round(m * inStep).toLong()).coerceAtLeast(0L)
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
        val totalSamples = requestedCrossfadeMs * SAMPLE_RATE / 1000L
        return nonSyncWindowRecipe(
            TransitionStrategy.BEATMATCHED_BLEND, totalSamples, contentEndMs, rateRatio,
            (recoveryMs * SAMPLE_RATE / 1000L).coerceAtMost(totalSamples / 2),
        )
    }

    private fun nonSyncWindowRecipe(
        strategy: TransitionStrategy,
        totalSamples: Long,
        contentEndMs: Long,
        rateRatio: Float,
        recoverySamples: Long,
        incomingStartSample: Long = 0L,
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
                incomingStartSample = incomingStartSample,
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
