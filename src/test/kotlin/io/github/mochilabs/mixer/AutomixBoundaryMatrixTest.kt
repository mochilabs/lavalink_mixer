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

    private fun plan(out: ExtendedBeatGrid?, inc: ExtendedBeatGrid?, outF: TransitionFeatures? = features(), inF: TransitionFeatures? = features(), crossfadeMs: Long = 5000L) =
        AdaptiveTransitionPlanner.plan(
            outgoingGridTail = out,
            incomingGridHead = inc,
            outgoingFeaturesTail = outF,
            incomingFeaturesHead = inF,
            requestedCrossfadeMs = crossfadeMs,
            contentEndMs = 60000L,
        )

    @Test
    fun `scenario 1 - standard EDM 128 to 128 selects 16-beat PhraseMatchedBassSwap`() {
        val out = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, plan(out, inc, crossfadeMs = 8000L).strategy)
    }

    @Test
    fun `scenario 2 - weak phrase confidence selects 8-beat ShortBassSwap`() {
        val out = ExtendedBeatGrid(124f, 0L, 0.8f, 0L, 0.6f, 0.3f)
        val inc = ExtendedBeatGrid(122f, 0L, 0.8f, 0L, 0.7f, 0.2f)
        assertEquals(TransitionStrategy.SHORT_BASS_SWAP, plan(out, inc).strategy)
    }

    @Test
    fun `scenario 3 - weak downbeat confidence selects BeatmatchedBlend`() {
        val out = ExtendedBeatGrid(130f, 0L, 0.8f, 0L, 0.3f, 0.1f)
        val inc = ExtendedBeatGrid(130f, 0L, 0.8f, 0L, 0.2f, 0.1f)
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, plan(out, inc).strategy)
    }

    @Test
    fun `scenario 4 - double-time 174 DnB to 87 Trap normalizes and selects Bass Swap`() {
        val out = ExtendedBeatGrid(174f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(87f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val recipe = plan(out, inc)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
        assertEquals(1.0f, recipe.sync.rateRatio, 0.01f)
    }

    @Test
    fun `scenario 5 - deadband boundary with compatible energy selects Sync Bass Swap`() {
        val out = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(129.5f, 0L, 0.9f, 0L, 0.8f, 0.7f) // 7.9% diff in the deadband around 8%
        val featOut = features(rms = 0.15f)
        val featIn = features(rms = 0.14f)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, plan(out, inc, featOut, featIn).strategy)
    }

    @Test
    fun `scenario 6 - deadband boundary with energy mismatch selects a plain crossfade`() {
        val out = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val inc = ExtendedBeatGrid(129.5f, 0L, 0.9f, 0L, 0.8f, 0.7f)
        val featOut = features(rms = 0.05f)
        val featIn = features(rms = 0.25f) // ratio = 5.0 > 3.98 (14 dB mismatch)
        val recipe = plan(out, inc, featOut, featIn)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals("tempo_diff_exceeded", recipe.reason)
    }

    @Test
    fun `scenario 7 - extreme tempo gap with confident downbeats selects DownbeatCut`() {
        val out = ExtendedBeatGrid(130f, 0L, 0.9f, 0L, 0.8f)
        val inc = ExtendedBeatGrid(95f, 0L, 0.9f, 0L, 0.7f) // 36% gap
        assertEquals(TransitionStrategy.DOWNBEAT_CUT, plan(out, inc, features(rms = 0.2f), features(rms = 0.2f)).strategy)
    }

    @Test
    fun `scenario 8 - missing incoming metadata selects a plain crossfade`() {
        val out = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f)
        val recipe = plan(out, null, features(), null)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals("missing_incoming_grid", recipe.reason)
        assertTrue(!recipe.sync.enabled)
    }

    @Test
    fun `scenario 9 - non-4-4 meter incoming track degrades to BeatmatchedBlend`() {
        val out = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.8f)
        val inc = ExtendedBeatGrid(120f, 0L, 0.9f, 0L, 0.0f, isNon44Meter = true) // 3/4 waltz
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, plan(out, inc).strategy)
    }

    @Test
    fun `scenario 10 - missing all metadata falls back to Level 2 Absolute Fallback 5s Equal-Power`() {
        val recipe = AdaptiveTransitionPlanner.plan(
            outgoingGridTail = null,
            incomingGridHead = null,
            outgoingFeaturesTail = null,
            incomingFeaturesHead = null,
            requestedCrossfadeMs = 5000L,
            contentEndMs = 60000L,
        )
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals(48000L * 5L, recipe.totalSamples)
    }

    @Test
    fun `low-confidence incoming grid is reported separately from a missing one`() {
        val out = ExtendedBeatGrid(128f, 0L, 0.9f, 0L, 0.8f)
        val weak = ExtendedBeatGrid(128f, 0L, 0.1f, 0L, 0.8f)
        val recipe = plan(out, weak)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals("low_confidence_incoming_grid", recipe.reason)
    }
}
