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
    fun `plain crossfade when the tempo gap is too wide and the incoming downbeat is uncertain`() {
        val outGrid = ExtendedBeatGrid(bpm = 140f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f)
        val inGrid = ExtendedBeatGrid(bpm = 100f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.2f) // downbeat uncertain

        val recipe = plan(outGrid, inGrid)
        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals("tempo_diff_exceeded", recipe.reason)
        assertEquals(5000L * 48L, recipe.totalSamples)
        assertEquals(1.0f, recipe.sync.rateRatio)
        assertTrue(recipe.gainAt(TrackTarget.INCOMING, 0L, 0.0f) < 0.05f, "incoming must fade in from 0")
        assertEquals(1.0f, recipe.gainAt(TrackTarget.INCOMING, recipe.totalSamples, 0.0f), 1e-4f)
        assertTrue(recipe.gainAt(TrackTarget.OUTGOING, 0L, 1.0f) > 0.95f, "outgoing must start at full level")
        assertEquals(0.0f, recipe.gainAt(TrackTarget.OUTGOING, recipe.totalSamples, 1.0f), 1e-4f)
        // Equal power: summed power of uncorrelated material stays flat, no level bump.
        for (i in 0..10) {
            val at = recipe.totalSamples * i / 10
            val o = recipe.gainAt(TrackTarget.OUTGOING, at, 1.0f)
            val n = recipe.gainAt(TrackTarget.INCOMING, at, 0.0f)
            assertEquals(1.0f, o * o + n * n, 1e-3f, "power at step $i")
        }
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

    @Test
    fun `unstable incoming rhythm skips tempo sync for a plain crossfade`() {
        // Mirrors a soft intro: a weak half-time grid that octave-normalizes to a 3.6% stretch.
        val outGrid = ExtendedBeatGrid(bpm = 124f, phaseSample = 0L, bpmConfidence = 0.33f, downbeatConfidence = 0.24f, phraseConfidence = 0.99f)
        val inGrid = ExtendedBeatGrid(bpm = 64.34f, phaseSample = 0L, bpmConfidence = 0.28f, downbeatConfidence = 0.05f, phraseConfidence = 0.08f)
        val outF = TransitionFeatures(0, 30000, emptyList(), 0.35f, 0.93f, KeyEstimate.Unknown("test"), emptyList())
        val inF = TransitionFeatures(0, 30000, emptyList(), 0.28f, 0.23f, KeyEstimate.Unknown("test"), emptyList())

        val recipe = AdaptiveTransitionPlanner.plan(
            outgoingGridTail = outGrid,
            incomingGridHead = inGrid,
            outgoingFeaturesTail = outF,
            incomingFeaturesHead = inF,
            requestedCrossfadeMs = 9000L,
            contentEndMs = 255000L,
            minConfidence = 0.2f,
        )

        assertEquals(TransitionStrategy.ABSOLUTE_FALLBACK, recipe.strategy)
        assertEquals("unstable_rhythm", recipe.reason)
        assertTrue(!recipe.sync.enabled)
        assertEquals(1.0f, recipe.sync.rateRatio)
    }

    @Test
    fun `tempo recovery lasts at least four beats and grows with the tempo change`() {
        val periodMs = 60_000.0 / 120.0
        assertEquals(0L, AdaptiveTransitionPlanner.recoveryMsFor(1.0f, periodMs))
        assertEquals(2000L, AdaptiveTransitionPlanner.recoveryMsFor(0.9636f, periodMs))
        // 11.1% needs 3.33 s, rounded up to seven beats.
        assertEquals(3500L, AdaptiveTransitionPlanner.recoveryMsFor(1.1111f, periodMs))
    }

    @Test
    fun `tight crossfade shrinks recovery so a two-beat fade still fits`() {
        val periodMs = 60_000.0 / 124.0
        // 2500 ms leaves room for 3 recovery beats after a 2-beat fade, not the preferred 4.
        val recovery = AdaptiveTransitionPlanner.recoveryMsFor(0.9636f, periodMs, 2500L)
        assertEquals(Math.round(3 * periodMs), recovery)
        // Never below the minimum glide, even when nothing fits.
        assertEquals(Math.round(2 * periodMs), AdaptiveTransitionPlanner.recoveryMsFor(0.9636f, periodMs, 500L))

        val grid = ExtendedBeatGrid(bpm = 124f, phaseSample = 0L, bpmConfidence = 0.9f)
        val slower = ExtendedBeatGrid(bpm = 128.68f, phaseSample = 0L, bpmConfidence = 0.9f)
        val recipe = AdaptiveTransitionPlanner.plan(grid, slower, features(), features(), 2500L, 60000L)
        assertEquals(TransitionStrategy.BEATMATCHED_BLEND, recipe.strategy)
        assertTrue(recipe.sync.enabled)
    }

    @Test
    fun `unstable rhythm does not block a unity-tempo sync`() {
        val grid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val unstable = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.2f, KeyEstimate.Unknown("test"), emptyList())
        val recipe = plan(grid, grid, features(), unstable)
        assertTrue(recipe.sync.enabled, "no stretch is needed, so the weak rhythm gate must not apply: ${recipe.reason}")
        assertEquals(1.0f, recipe.sync.rateRatio)
    }
}
