package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomixBoundaryMatrixTest {
    private fun features(rms: Float = 0.2f, stability: Float = 0.9f) = TransitionFeatures(
        windowStartMs = 0L,
        windowEndMs = 10000L,
        energyEnvelope = emptyList(),
        rms = rms,
        rhythmicStability = stability,
        key = KeyEstimate.Unknown("percussive"),
        candidates = emptyList(),
    )

    @Test
    fun `scenario 1 - standard EDM 128 to 128 selects 16-beat PhraseMatchedBassSwap`() {
        val out = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, features(), features(), 5000L)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
    }

    @Test
    fun `scenario 2 - weak phrase confidence selects 8-beat ShortBassSwap`() {
        val out = ExtendedBeatGrid(124f, 0L, 0.8f, 0L, 0.6f, 0.3f)
        val inc = ExtendedBeatGrid(122f, 0L, 0.8f, 0L, 0.7f, 0.2f)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, features(), features(), 5000L)
        assertEquals(TransitionStrategy.SHORT_BASS_SWAP, recipe.strategy)
    }

    @Test
    fun `scenario 3 - weak downbeat confidence selects BeatmatchedBlend`() {
        val out = ExtendedBeatGrid(130f, 0L, 0.8f, 0L, 0.3f, 0.1f)
        val inc = ExtendedBeatGrid(130f, 0L, 0.8f, 0L, 0.2f, 0.1f)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, features(), features(), 5000L)
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, recipe.strategy)
    }

    @Test
    fun `scenario 4 - double-time 174 DnB to 87 Trap normalizes and selects Bass Swap`() {
        val out = ExtendedBeatGrid(174f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(87f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, features(), features(), 5000L)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
        assertEquals(1.0f, recipe.sync.rateRatio, 0.01f)
    }

    @Test
    fun `scenario 5 - deadband boundary with compatible energy selects Sync Bass Swap`() {
        val out = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(127.0f, 0L, 0.9f, 0L, 0.8f, 0.7f) // 5.8% diff in deadband
        val featOut = features(rms = 0.15f)
        val featIn = features(rms = 0.14f)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, featOut, featIn, 5000L)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
    }

    @Test
    fun `scenario 6 - deadband boundary with energy mismatch selects Echo Out`() {
        val out = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(127.0f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val featOut = features(rms = 0.05f)
        val featIn = features(rms = 0.25f) // ratio = 5.0 > 3.16 (14 dB mismatch)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, featOut, featIn, 5000L)
        assertEquals(TransitionStrategy.ECHO_OUT, recipe.strategy)
    }

    @Test
    fun `scenario 7 - extreme tempo gap with confident downbeats selects DownbeatCut`() {
        val out = ExtendedBeatGrid(130f, 0L, 0.9f, 0L, 0.8f)
        val inc = ExtendedBeatGrid(95f, 0L, 0.9f, 0L, 0.7f) // 36% gap
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, features(rms = 0.2f), features(rms = 0.2f), 5000L)
        assertEquals(TransitionStrategy.DOWNBEAT_CUT, recipe.strategy)
    }

    @Test
    fun `scenario 8 - missing incoming metadata selects Echo Out using outgoing tempo`() {
        val out = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f)
        val recipe = AdaptiveTransitionPlannerV2.plan(out, null, features(), null, 5000L)
        assertEquals(TransitionStrategy.ECHO_OUT, recipe.strategy)
        // 128 BPM >= 125 BPM -> 0.5-beat delay (234ms = 11250 samples @ 48kHz)
        val echo = recipe.automations.filterIsInstance<ParamAutomation.EchoTrigger>().first()
        assertEquals(11250, echo.delaySamples)
    }

    @Test
    fun `scenario 9 - non-4-4 meter incoming track degrades to BeatmatchedBlend`() {
        val out = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.8f)
        val inc = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.0f, isNon44Meter = true) // 3/4 waltz
        val recipe = AdaptiveTransitionPlannerV2.plan(out, inc, features(), features(), 5000L)
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, recipe.strategy)
    }

    @Test
    fun `scenario 10 - missing all metadata falls back to Level 2 Absolute Fallback 5s Equal-Power`() {
        val recipe = AdaptiveTransitionPlannerV2.plan(null, null, null, null, 5000L)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals(48000L * 5L, recipe.totalSamples)
    }
}
