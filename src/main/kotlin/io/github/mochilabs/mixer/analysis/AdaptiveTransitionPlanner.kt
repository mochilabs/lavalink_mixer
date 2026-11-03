package io.github.mochilabs.mixer

import kotlin.math.abs

sealed interface AdaptiveTransitionDecision {
    data class Preserve(val reason: String) : AdaptiveTransitionDecision
    data class SilenceTrim(val outgoingContentEndMs: Long, val incomingStartMs: Long, val reason: String) : AdaptiveTransitionDecision
    data class ShortCrossfade(val durationMs: Long, val reason: String, val outgoingContentEndMs: Long, val incomingStartMs: Long) : AdaptiveTransitionDecision
    data class BeatMatched(val transition: TransitionDecision.BeatMatched) : AdaptiveTransitionDecision
}

/** Conservative local-window policy. These thresholds are heuristics, not calibrated genre classifiers. */
object AdaptiveTransitionPlanner {
    fun decide(
        outgoingFeatures: TransitionFeatures?,
        incomingFeatures: TransitionFeatures?,
        outgoingGrid: BeatGrid?,
        incomingGrid: BeatGrid?,
        contentEndMs: Long,
        requestedCrossfadeMs: Long,
        preserveContext: Boolean = false,
        crossfadeEnabled: Boolean = true,
        beatmatchEnabled: Boolean = true,
        maxTempoChange: Float = BeatMatchPolicy.DEFAULT_MAX_TEMPO_CHANGE,
        minConfidence: Float = BeatMatchPolicy.DEFAULT_MIN_CONFIDENCE,
        incomingLeadMs: Long = 0L,
        incomingDurationMs: Long = -1L,
    ): AdaptiveTransitionDecision {
        if (preserveContext) return AdaptiveTransitionDecision.Preserve("preserve_context")
        var selectedEnd = contentEndMs
        var selectedStart = incomingLeadMs.coerceAtLeast(0L)

        fun short(reason: String) = AdaptiveTransitionDecision.ShortCrossfade(
            minOf(requestedCrossfadeMs, selectedEnd),
            reason,
            selectedEnd,
            selectedStart,
        )

        if (outgoingFeatures == null || incomingFeatures == null) return short("missing_features")
        if (outgoingFeatures.unknownReason != null || incomingFeatures.unknownReason != null) return short("unknown_features")

        val hasBeatGrids = beatmatchEnabled &&
            outgoingGrid != null && incomingGrid != null &&
            outgoingGrid.bpm.isFinite() && incomingGrid.bpm.isFinite() &&
            outgoingGrid.bpm > 0f && incomingGrid.bpm > 0f &&
            outgoingGrid.confidence >= minConfidence && incomingGrid.confidence >= minConfidence

        if (outgoingFeatures.trailingSilenceMs >= 100 || incomingFeatures.leadingSilenceMs >= 100) {
            val end = if (outgoingFeatures.trailingSilenceMs > 0) {
                minOf(contentEndMs, outgoingFeatures.windowEndMs - outgoingFeatures.trailingSilenceMs)
                    .coerceIn(1L, contentEndMs)
            } else contentEndMs
            val start = maxOf(incomingLeadMs, incomingFeatures.windowStartMs + incomingFeatures.leadingSilenceMs)

            // If crossfade is disabled or no beat grids exist to mix, trim silence directly
            if (!crossfadeEnabled || requestedCrossfadeMs <= 0 || !hasBeatGrids) {
                if (end > 0 && (incomingDurationMs <= 0 || start < incomingDurationMs - 500)) {
                    return AdaptiveTransitionDecision.SilenceTrim(end, start, "boundary_silence")
                }
            }

            // When crossfade + beat matching are active on valid beat grids,
            // trim silence from boundaries so beat matching connects the audible music
            selectedEnd = end
            selectedStart = start
        }

        if (!crossfadeEnabled || contentEndMs <= 0 || requestedCrossfadeMs <= 0) {
            return AdaptiveTransitionDecision.Preserve("crossfade_disabled")
        }

        val selected = selectBoundaries(outgoingFeatures, incomingFeatures, outgoingGrid, incomingGrid,
            selectedEnd, selectedStart, incomingDurationMs, maxTempoChange)
        selectedEnd = selected.first
        selectedStart = selected.second

        if (outgoingFeatures.rms < 0.008f || incomingFeatures.rms < 0.008f) return short("low_energy")
        val energyRatio = maxOf(outgoingFeatures.rms, incomingFeatures.rms) / minOf(outgoingFeatures.rms, incomingFeatures.rms)
        if (energyRatio > 3.16f) return short("energy_mismatch")
        val outStability = outgoingFeatures.rhythmicStability ?: return short("unknown_rhythm")
        val inStability = incomingFeatures.rhythmicStability ?: return short("unknown_rhythm")
        if (minOf(outStability, inStability) < 0.45f) return short("unstable_rhythm")
        if (keyClash(outgoingFeatures.key, incomingFeatures.key)) return short("key_clash")

        val skipLimitMs = maxOf(4000L, requestedCrossfadeMs)
        return when (val result = BeatMatchPolicy.decide(
            outgoingGrid, incomingGrid, selectedEnd, requestedCrossfadeMs,
            crossfadeEnabled, beatmatchEnabled, maxTempoChange, minConfidence, selectedStart, incomingDurationMs,
        )) {
            is TransitionDecision.BeatMatched -> if (result.incomingStartMs > selectedStart + skipLimitMs ||
                result.handoffMs < selectedEnd - skipLimitMs) short("boundary_skip_limit")
                else AdaptiveTransitionDecision.BeatMatched(result)
            is TransitionDecision.EqualPower -> short(result.fallbackReason)
            is TransitionDecision.Gapless -> AdaptiveTransitionDecision.Preserve(result.fallbackReason)
        }
    }

    fun toCrossfadePlan(decision: AdaptiveTransitionDecision, contentEndMs: Long): CrossfadePlan? = when (decision) {
        is AdaptiveTransitionDecision.BeatMatched -> BeatMatchPolicy.toCrossfadePlan(decision.transition, contentEndMs, decision.transition.fadeMs)
        is AdaptiveTransitionDecision.ShortCrossfade -> TrimTransition.crossfade(decision.outgoingContentEndMs, decision.durationMs)?.copy(
            fallbackReason = decision.reason, incomingStartMs = decision.incomingStartMs,
        )
        is AdaptiveTransitionDecision.Preserve, is AdaptiveTransitionDecision.SilenceTrim -> null
    }

    private fun selectBoundaries(
        outgoing: TransitionFeatures,
        incoming: TransitionFeatures,
        outgoingGrid: BeatGrid?,
        incomingGrid: BeatGrid?,
        contentEndMs: Long,
        incomingLeadMs: Long,
        incomingDurationMs: Long,
        maxTempoChange: Float,
    ): Pair<Long, Long> {
        val originalStart = incomingLeadMs.coerceAtLeast(0L)
        fun energyAt(features: TransitionFeatures, position: Long) =
            features.energyEnvelope.minByOrNull { abs(it.positionMs - position) }?.rms ?: features.rms
        val ends = listOf(TransitionCandidate(contentEndMs, energyAt(outgoing, contentEndMs))) +
            outgoing.candidates.filter { it.positionMs in maxOf(1L, contentEndMs - 2000)..contentEndMs }.take(8)
        val starts = listOf(TransitionCandidate(originalStart, energyAt(incoming, originalStart))) +
            incoming.candidates.filter {
                it.positionMs in originalStart..originalStart + 2000 &&
                    (incomingDurationMs <= 0 || it.positionMs < incomingDurationMs - 500)
            }.take(8)
        fun phaseCost(position: Long, grid: BeatGrid?): Double {
            if (grid == null || !grid.periodMs.isFinite() || grid.periodMs <= 0) return 0.0
            val phase = ((position - grid.phaseMs) % grid.periodMs + grid.periodMs) % grid.periodMs
            return minOf(phase, grid.periodMs - phase) / grid.periodMs
        }
        val ratio = if (outgoingGrid != null && incomingGrid != null && incomingGrid.bpm > 0)
            outgoingGrid.bpm / incomingGrid.bpm else Float.NaN
        val tempoCost = if (ratio.isFinite()) (abs(ratio - 1f) / maxOf(0.001f, maxTempoChange)).coerceAtMost(1f) else 1f
        fun cost(end: TransitionCandidate, start: TransitionCandidate): Double {
            val energy = abs(end.energy - start.energy) / maxOf(0.001f, end.energy, start.energy)
            val skip = (contentEndMs - end.positionMs + start.positionMs - originalStart) / 4000.0
            val phase = phaseCost(end.positionMs, outgoingGrid) + phaseCost(start.positionMs, incomingGrid)
            return energy * 0.55 + skip * 0.30 + phase * 0.10 + tempoCost * 0.05
        }
        var best = ends.first() to starts.first()
        var bestCost = cost(best.first, best.second)
        // Retain the original boundaries unless the measured improvement exceeds a small margin.
        for (end in ends) for (start in starts) {
            val candidateCost = cost(end, start)
            if (candidateCost + 0.03 < bestCost) {
                best = end to start
                bestCost = candidateCost
            }
        }
        return best.first.positionMs to best.second.positionMs
    }

    private fun keyClash(a: KeyEstimate, b: KeyEstimate): Boolean {
        if (a !is KeyEstimate.Candidate || b !is KeyEstimate.Candidate || minOf(a.confidence, b.confidence) < 0.6f) return false
        val relativeA = (a.pitchClass + if (a.mode == KeyMode.MINOR) 3 else 0) % 12
        val relativeB = (b.pitchClass + if (b.mode == KeyMode.MINOR) 3 else 0) % 12
        val distance = abs(relativeA - relativeB)
        return distance !in setOf(0, 5, 7)
    }
}
