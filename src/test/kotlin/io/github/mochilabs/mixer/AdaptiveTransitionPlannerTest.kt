package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveTransitionPlannerTest {
    private fun features() = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

    private fun plan(out: ExtendedBeatGrid?, inc: ExtendedBeatGrid?, outF: TransitionFeatures? = features(), inF: TransitionFeatures? = features()) =
        AdaptiveTransitionPlanner.plan(
            outgoingGridTail = out,
            incomingGridHead = inc,
            outgoingFeaturesTail = outF,
            incomingFeaturesHead = inF,
            requestedCrossfadeMs = 5000L,
            contentEndMs = 60000L,
        )

    @Test
    fun `plans PhraseMatchedBassSwap when BPM, downbeats, and phrases are all confident`() {
        val outGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val inGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)

        val recipe = plan(outGrid, inGrid)

        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
        assertTrue(recipe.sync.enabled)
        assertEquals(1.0f, recipe.sync.rateRatio)
        assertTrue(recipe.automations.any { it is ParamAutomation.BassCut }, "must contain BassCut automations")
    }

    @Test
    fun `degrades to ShortBassSwap when phrase confidence is low`() {
        val outGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.2f)
        val inGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.2f)

        assertEquals(TransitionStrategy.SHORT_BASS_SWAP, plan(outGrid, inGrid).strategy)
    }

    @Test
    fun `degrades to BeatmatchedBlend when downbeat confidence is low`() {
        val outGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f)
        val inGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f)

        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, plan(outGrid, inGrid).strategy)
    }

    @Test
    fun `selects Echo Out when BPM difference exceeds 6 percent and incoming downbeat is uncertain`() {
        val outGrid = ExtendedBeatGrid(bpm = 140f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val inGrid = ExtendedBeatGrid(bpm = 100f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f) // downbeat uncertain

        val recipe = plan(outGrid, inGrid)
        assertEquals(TransitionStrategy.ECHO_OUT, recipe.strategy)
        assertTrue(recipe.automations.any { it is ParamAutomation.EchoTrigger })
    }

    @Test
    fun `selects Downbeat Cut when BPM difference exceeds 6 percent and both downbeats are confident`() {
        val outGrid = ExtendedBeatGrid(bpm = 140f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val inGrid = ExtendedBeatGrid(bpm = 100f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)

        assertEquals(TransitionStrategy.DOWNBEAT_CUT, plan(outGrid, inGrid).strategy)
    }

    @Test
    fun `normalizes double-time 174 DnB to 87 Trap and selects Bass Swap`() {
        val outGrid = ExtendedBeatGrid(bpm = 174f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val inGrid = ExtendedBeatGrid(bpm = 87f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)

        val recipe = plan(outGrid, inGrid)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
        assertEquals(1.0f, recipe.sync.rateRatio, 0.01f, "174 vs 87 must normalize to 1.0 ratio using m=0.5")
    }

    @Test
    fun `falls back to Level 1 Filter Sweep when beat grid is missing but features exist`() {
        assertEquals(TransitionStrategy.DEGRADED_FILTER_SWEEP, plan(null, null).strategy)
    }

    @Test
    fun `falls back to Level 2 Absolute Fallback when all features are missing`() {
        val recipe = AdaptiveTransitionPlanner.plan(
            outgoingGridTail = null,
            incomingGridHead = null,
            outgoingFeaturesTail = null,
            incomingFeaturesHead = null,
            requestedCrossfadeMs = 5000L,
            contentEndMs = 60000L,
        )
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
    }

    private fun planWith(
        crossfadeMs: Long,
        contentEndMs: Long = 60000L,
        out: ExtendedBeatGrid? = null,
        inc: ExtendedBeatGrid? = null,
        incDurationMs: Long = -1L,
        bpm: Boolean = true,
    ) = AdaptiveTransitionPlanner.plan(
        outgoingGridTail = out,
        incomingGridHead = inc,
        outgoingFeaturesTail = if (bpm) features() else null,
        incomingFeaturesHead = if (bpm) features() else null,
        requestedCrossfadeMs = crossfadeMs,
        contentEndMs = contentEndMs,
        incomingDurationMs = incDurationMs,
    )

    @Test
    fun `fallback blend keeps the requested crossfade length in samples when the sync window is too short`() {
        val g = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        // Incoming track too short for any sync window -> falls back to a plain blend.
        val recipe = planWith(8000L, out = g, inc = g, incDurationMs = 3000L)
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, recipe.strategy)
        assertEquals(8000L * 48, recipe.totalSamples)
    }

    @Test
    fun `absolute fallback honors configured crossfade and available content`() {
        assertEquals(2000L * 48, planWith(2000L, bpm = false).totalSamples)
        assertEquals(3000L * 48, planWith(8000L, contentEndMs = 3000L, bpm = false).totalSamples)
    }

    @Test
    fun `uses a plain crossfade when no beat count fits the configured window`() {
        val g = ExtendedBeatGrid(bpm = 70f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val recipe = planWith(1500L, out = g, inc = g)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals("fade_window_too_short", recipe.reason)
        assertEquals(1500L * 48, recipe.totalSamples)
    }

    @Test
    fun `non-positive crossfade yields no overlap`() {
        assertEquals(0L, planWith(0L, bpm = false).totalSamples)
    }

    @Test
    fun `bass swap lands inside the fade rather than at its end`() {
        val g = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val recipe = plan(g, g)
        val fadeEnd = recipe.totalSamples - recipe.sync.recoverySamples
        val swap = recipe.bassCuts.filter { it.atSample > 0L }.map { it.atSample }
        assertTrue(swap.isNotEmpty() && swap.all { it in 1 until fadeEnd }, "swap=$swap fadeEnd=$fadeEnd")
    }
}
