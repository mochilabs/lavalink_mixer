package io.github.mochilabs.mixer

import kotlin.math.*

object AdaptiveTransitionPlannerV2 {
    private const val LOG_6_PERCENT = 0.08406 // log2(1.06)
    private const val SAMPLE_RATE = 48000

    fun plan(
        outgoingGridTail: ExtendedBeatGrid?,
        incomingGridHead: ExtendedBeatGrid?,
        outgoingFeaturesTail: TransitionFeatures?,
        incomingFeaturesHead: TransitionFeatures?,
        requestedCrossfadeMs: Long,
        preserveContext: Boolean = false,
    ): TransitionRecipe {
        if (preserveContext) {
            return TransitionRecipe(
                strategy = TransitionStrategy.ABSOLUTE_FALLBACK,
                totalSamples = 0L,
                sync = BeatSyncConfig(enabled = false, targetBpm = 0f),
                reason = "preserve_context",
            )
        }

        val outGrid = outgoingGridTail?.takeIf { it.bpmConfidence >= 0.3f && it.bpm.isFinite() && it.bpm > 0f }
        val inGrid = incomingGridHead?.takeIf { it.bpmConfidence >= 0.3f && it.bpm.isFinite() && it.bpm > 0f }

        // Level 0: Outgoing grid is valid
        if (outGrid != null) {
            if (inGrid != null) {
                // 1. Symmetrical octave-normalized log2 tempo comparison (1.0 -> 0.5 -> 2.0)
                val multipliers = listOf(1.0, 0.5, 2.0)
                val bestM = multipliers.minByOrNull { m -> abs(log2((outGrid.bpm * m) / inGrid.bpm)) } ?: 1.0
                val deltaLog = abs(log2((outGrid.bpm * bestM) / inGrid.bpm))

                // 2. Dynamic uncertainty deadband
                val minBpmConf = min(outGrid.bpmConfidence, inGrid.bpmConfidence)
                val margin = max(0.003, 0.015 * (1.0 - minBpmConf))
                val thresholdLow = LOG_6_PERCENT - margin
                val thresholdHigh = LOG_6_PERCENT + margin

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

                if (isSync) {
                    val rateRatio = ((outGrid.bpm * bestM) / inGrid.bpm).toFloat()
                    val hasDownbeats = outGrid.downbeatConfidence >= 0.5f && inGrid.downbeatConfidence >= 0.5f &&
                        !outGrid.isNon44Meter && !inGrid.isNon44Meter

                    if (hasDownbeats) {
                        val hasPhrases = outGrid.phraseConfidence >= 0.5f && inGrid.phraseConfidence >= 0.5f
                        val fadeBeats = if (hasPhrases) 16 else 8
                        val cutBar = if (hasPhrases) 4 else 2
                        val periodSamples = (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())
                        val totalSamples = Math.round(fadeBeats * periodSamples)
                        val swapSample = Math.round(cutBar * 4 * periodSamples)

                        return TransitionRecipe(
                            strategy = if (hasPhrases) TransitionStrategy.PHRASE_MATCHED_BASS_SWAP else TransitionStrategy.SHORT_BASS_SWAP,
                            totalSamples = totalSamples,
                            sync = BeatSyncConfig(
                                enabled = true,
                                targetBpm = outGrid.bpm,
                                rateRatio = rateRatio,
                                startSample = 0L,
                                handoffSample = totalSamples,
                            ),
                            automations = listOf(
                                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, totalSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, totalSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
                                ParamAutomation.BassCut(TrackTarget.INCOMING, 0L, muteLow = true),
                                ParamAutomation.BassCut(TrackTarget.INCOMING, swapSample, muteLow = false),
                                ParamAutomation.BassCut(TrackTarget.OUTGOING, swapSample, muteLow = true),
                            ),
                        )
                    }

                    // Beatmatched Blend: volume only, 4 beats
                    val periodSamples = (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())
                    val totalSamples = Math.round(4 * periodSamples)
                    return TransitionRecipe(
                        strategy = TransitionStrategy.BEATMATCHED_BLEND,
                        totalSamples = totalSamples,
                        sync = BeatSyncConfig(
                            enabled = true,
                            targetBpm = outGrid.bpm,
                            rateRatio = rateRatio,
                            startSample = 0L,
                            handoffSample = totalSamples,
                        ),
                        automations = listOf(
                            ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, totalSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                            ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, totalSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
                        ),
                    )
                }

                // Async domain: Downbeat Cut check
                if (outGrid.downbeatConfidence >= 0.6f && inGrid.downbeatConfidence >= 0.6f) {
                    val highEnergy = (outgoingFeaturesTail?.rms ?: 0f) > 0.1f && (incomingFeaturesHead?.rms ?: 0f) > 0.1f
                    if (highEnergy) {
                        return TransitionRecipe(
                            strategy = TransitionStrategy.DOWNBEAT_CUT,
                            totalSamples = 480L, // 10ms quick cut
                            sync = BeatSyncConfig(enabled = false, targetBpm = inGrid.bpm),
                            automations = listOf(
                                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, 480L, 1.0f, 0.0f, RampCurve.LINEAR),
                                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, 480L, 0.0f, 1.0f, RampCurve.LINEAR),
                            ),
                        )
                    }
                }
            }

            // Async fallback or incoming grid missing: Echo Out on outgoing tempo
            val delayBeats = if (outGrid.bpm >= 125f) 0.5f else 1.0f
            val delaySamples = Math.round(delayBeats * (SAMPLE_RATE * 60.0 / outGrid.bpm.toDouble())).toInt()
            val totalSamples = 96000L // 2.0s
            return TransitionRecipe(
                strategy = TransitionStrategy.ECHO_OUT,
                totalSamples = totalSamples,
                sync = BeatSyncConfig(enabled = false, targetBpm = outGrid.bpm),
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
            )
        }

        // Level 1: BeatGrid failed, but waveform features are valid
        if (outgoingFeaturesTail != null && incomingFeaturesHead != null &&
            outgoingFeaturesTail.unknownReason == null && incomingFeaturesHead.unknownReason == null) {
            val totalSamples = minOf(requestedCrossfadeMs, 4000L) * SAMPLE_RATE / 1000L
            return TransitionRecipe(
                strategy = TransitionStrategy.DEGRADED_FILTER_SWEEP,
                totalSamples = totalSamples,
                sync = BeatSyncConfig(enabled = false, targetBpm = 0f),
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
            sync = BeatSyncConfig(enabled = false, targetBpm = 0f),
            automations = listOf(
                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, totalSamples, 1.0f, 0.0f, RampCurve.EQUAL_POWER),
                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, totalSamples, 0.0f, 1.0f, RampCurve.EQUAL_POWER),
            ),
            reason = "missing_all_metadata",
        )
    }

    private fun keyClash(a: KeyEstimate?, b: KeyEstimate?): Boolean {
        if (a == null || b == null || a !is KeyEstimate.Candidate || b !is KeyEstimate.Candidate || minOf(a.confidence, b.confidence) < 0.6f) return false
        val relA = (a.pitchClass + if (a.mode == KeyMode.MINOR) 3 else 0) % 12
        val relB = (b.pitchClass + if (b.mode == KeyMode.MINOR) 3 else 0) % 12
        val distance = abs(relA - relB)
        return distance !in setOf(0, 5, 7)
    }
}
