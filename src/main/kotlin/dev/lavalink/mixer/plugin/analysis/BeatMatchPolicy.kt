package dev.lavalink.mixer.plugin

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

sealed interface TransitionDecision {
    data class BeatMatched(
        val fadeBeats: Int,
        val fadeMs: Long,
        val targetBpm: Float,
        val rateRatio: Float,
        val startMs: Long,
        val handoffMs: Long,
        val incomingStartMs: Long,
    ) : TransitionDecision

    data class EqualPower(val fallbackReason: String) : TransitionDecision
    data class Gapless(val fallbackReason: String) : TransitionDecision
}

object BeatMatchPolicy {
    const val DEFAULT_MIN_CONFIDENCE: Float = 0.30f
    const val DEFAULT_MAX_TEMPO_CHANGE: Float = 0.06f

    fun decide(
        outgoing: BeatGrid?,
        incoming: BeatGrid?,
        contentEndMs: Long,
        requestedCrossfadeMs: Long,
        crossfadeEnabled: Boolean = true,
        beatmatchEnabled: Boolean = true,
        maxTempoChange: Float = DEFAULT_MAX_TEMPO_CHANGE,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
        incomingLeadMs: Long = 0L,
        incomingDurationMs: Long = -1L,
    ): TransitionDecision {
        if (!crossfadeEnabled || contentEndMs <= 0L || requestedCrossfadeMs <= 0L) {
            return TransitionDecision.Gapless("crossfade_disabled")
        }
        if (!beatmatchEnabled) {
            return TransitionDecision.EqualPower("beatmatch_disabled")
        }
        if (outgoing == null) {
            return TransitionDecision.EqualPower("missing_outgoing_grid")
        }
        if (incoming == null) {
            return TransitionDecision.EqualPower("missing_incoming_grid")
        }
        if (outgoing.bpm <= 0f || incoming.bpm <= 0f) {
            return TransitionDecision.EqualPower("invalid_bpm")
        }
        if (outgoing.confidence < minConfidence || incoming.confidence < minConfidence) {
            return TransitionDecision.EqualPower("low_confidence")
        }
        val rateRatio = outgoing.bpm / incoming.bpm
        if (abs(rateRatio - 1.0f) > maxTempoChange.coerceIn(0f, 0.12f)) {
            return TransitionDecision.EqualPower("tempo_diff_exceeded")
        }

        val maxFadeMs = minOf(requestedCrossfadeMs, contentEndMs)
        val fadeBeats = listOf(8, 4, 2).firstOrNull { b ->
            round(b * outgoing.periodMs).toLong() <= maxFadeMs
        } ?: return TransitionDecision.EqualPower("fade_window_too_short")
        val fadeMs = round(fadeBeats * outgoing.periodMs).toLong()

        val outPeriod = outgoing.periodMs
        val k = floor((contentEndMs - outgoing.phaseMs) / outPeriod).toLong()
        val snappedHandoff = (outgoing.phaseMs + round(k * outPeriod).toLong()).coerceIn(fadeMs, contentEndMs)
        val startMs = (snappedHandoff - fadeMs).coerceAtLeast(0L)
        val handoffMs = startMs + fadeMs

        val leadMs = incomingLeadMs.coerceAtLeast(0L)
        val inPeriod = incoming.periodMs
        val m = if (leadMs <= incoming.phaseMs) 0L else ceil((leadMs - incoming.phaseMs) / inPeriod).toLong()
        val rawIncomingStart = (incoming.phaseMs + round(m * inPeriod).toLong()).coerceAtLeast(0L)
        if (incomingDurationMs > 0 && rawIncomingStart >= incomingDurationMs - 500) {
            return TransitionDecision.EqualPower("incoming_track_too_short")
        }

        return TransitionDecision.BeatMatched(
            fadeBeats = fadeBeats,
            fadeMs = fadeMs,
            targetBpm = outgoing.bpm,
            rateRatio = rateRatio,
            startMs = startMs,
            handoffMs = handoffMs,
            incomingStartMs = rawIncomingStart,
        )
    }

    fun toCrossfadePlan(
        decision: TransitionDecision,
        contentEndMs: Long,
        requestedCrossfadeMs: Long,
    ): CrossfadePlan? = when (decision) {
        is TransitionDecision.BeatMatched -> CrossfadePlan(
            startMs = decision.startMs,
            handoffMs = decision.handoffMs,
            durationMs = decision.fadeMs,
            beatMatched = true,
            rateRatio = decision.rateRatio,
            incomingStartMs = decision.incomingStartMs,
            fadeBeats = decision.fadeBeats,
            targetBpm = decision.targetBpm,
        )
        is TransitionDecision.EqualPower -> TrimTransition.crossfade(contentEndMs, requestedCrossfadeMs)?.copy(
            beatMatched = false,
            fallbackReason = decision.fallbackReason,
        )
        is TransitionDecision.Gapless -> null
    }
}
