package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveTransitionPlannerV2Test {
    @Test
    fun `plans PhraseMatchedBassSwap when BPM, downbeats, and phrases are all confident`() {
        val outGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val inGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val recipe = AdaptiveTransitionPlannerV2.plan(
            outgoingGridTail = outGrid,
            incomingGridHead = inGrid,
            outgoingFeaturesTail = features,
            incomingFeaturesHead = features,
            requestedCrossfadeMs = 5000L,
        )

        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
        assertTrue(recipe.sync.enabled)
        assertEquals(1.0f, recipe.sync.rateRatio)
        assertTrue(recipe.automations.any { it is ParamAutomation.BassCut }, "must contain BassCut automations")
    }

    @Test
    fun `degrades to ShortBassSwap when phrase confidence is low`() {
        val outGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.2f)
        val inGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.2f)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val recipe = AdaptiveTransitionPlannerV2.plan(outGrid, inGrid, features, features, 5000L)
        assertEquals(TransitionStrategy.SHORT_BASS_SWAP, recipe.strategy)
    }

    @Test
    fun `degrades to BeatmatchedBlend when downbeat confidence is low`() {
        val outGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f)
        val inGrid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val recipe = AdaptiveTransitionPlannerV2.plan(outGrid, inGrid, features, features, 5000L)
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, recipe.strategy)
    }

    @Test
    fun `selects Echo Out when BPM difference exceeds 6 percent and incoming downbeat is uncertain`() {
        val outGrid = ExtendedBeatGrid(bpm = 140f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val inGrid = ExtendedBeatGrid(bpm = 100f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f) // downbeat uncertain
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val recipe = AdaptiveTransitionPlannerV2.plan(outGrid, inGrid, features, features, 5000L)
        assertEquals(TransitionStrategy.ECHO_OUT, recipe.strategy)
        assertTrue(recipe.automations.any { it is ParamAutomation.EchoTrigger })
    }

    @Test
    fun `selects Downbeat Cut when BPM difference exceeds 6 percent and both downbeats are confident`() {
        val outGrid = ExtendedBeatGrid(bpm = 140f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val inGrid = ExtendedBeatGrid(bpm = 100f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val recipe = AdaptiveTransitionPlannerV2.plan(outGrid, inGrid, features, features, 5000L)
        assertEquals(TransitionStrategy.DOWNBEAT_CUT, recipe.strategy)
    }
    @Test
    fun `normalizes double-time 174 DnB to 87 Trap and selects Bass Swap`() {
        val outGrid = ExtendedBeatGrid(bpm = 174f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val inGrid = ExtendedBeatGrid(bpm = 87f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val recipe = AdaptiveTransitionPlannerV2.plan(outGrid, inGrid, features, features, 5000L)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, recipe.strategy)
        assertEquals(1.0f, recipe.sync.rateRatio, 0.01f, "174 vs 87 must normalize to 1.0 ratio using m=0.5")
    }

    @Test
    fun `falls back to Level 1 Filter Sweep when beat grid is missing but features exist`() {
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())
        val recipe = AdaptiveTransitionPlannerV2.plan(null, null, features, features, 5000L)
        assertEquals(TransitionStrategy.DEGRADED_FILTER_SWEEP, recipe.strategy)
    }

    @Test
    fun `falls back to Level 2 Absolute Fallback when all features are missing`() {
        val recipe = AdaptiveTransitionPlannerV2.plan(null, null, null, null, 5000L)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
    }
}
