package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveTransitionPlannerTest {
    private val grid = BeatGrid(120f, 0, 0.9f)
    private fun features(key: KeyEstimate = KeyEstimate.Unknown("percussive")) = TransitionFeatures(
        windowStartMs = 0, windowEndMs = 10000, energyEnvelope = emptyList(), rms = 0.15f,
        rhythmicStability = 0.9f, key = key, candidates = emptyList(),
    )
    private fun decide(out: TransitionFeatures? = features(), inc: TransitionFeatures? = features(), preserve: Boolean = false) =
        AdaptiveTransitionPlanner.decide(out, inc, grid, grid, 10000, 5000, preserveContext = preserve)

    @Test
    fun `explicit preserve context bypasses even compatible beat matching`() {
        val result = assertIs<AdaptiveTransitionDecision.Preserve>(decide(preserve = true))
        assertEquals("preserve_context", result.reason)
        assertNull(AdaptiveTransitionPlanner.toCrossfadePlan(result, 10000))
    }

    @Test
    fun `missing and unknown features choose bounded fallback`() {
        val missing = assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(out = null))
        assertEquals("missing_features", missing.reason)
        assertEquals(1500L, missing.durationMs)
        assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(out = features().copy(unknownReason = "short_window")))
    }

    @Test
    fun `stable compatible grids can match without claiming unknown keys compatible`() {
        val result = assertIs<AdaptiveTransitionDecision.BeatMatched>(decide())
        assertEquals(1f, result.transition.rateRatio)
        assertTrue(assertNotNull(AdaptiveTransitionPlanner.toCrossfadePlan(result, 10000)).beatMatched)
    }

    @Test
    fun `only confident clashing keys shorten overlap`() {
        val c = features(KeyEstimate.Candidate(0, KeyMode.MAJOR, 0.9f))
        val fs = features(KeyEstimate.Candidate(6, KeyMode.MAJOR, 0.9f))
        assertEquals("key_clash", assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(c, fs)).reason)
        assertIs<AdaptiveTransitionDecision.BeatMatched>(decide(c, features(KeyEstimate.Candidate(9, KeyMode.MINOR, 0.9f))))
        assertIs<AdaptiveTransitionDecision.BeatMatched>(decide(c, features(KeyEstimate.Candidate(6, KeyMode.MAJOR, 0.2f))))
    }

    @Test
    fun `quiet or unstable boundaries do not receive long beatmatched transitions`() {
        assertEquals("low_energy", assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(inc = features().copy(rms = 0.002f))).reason)
        assertEquals("unstable_rhythm", assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(inc = features().copy(rhythmicStability = 0.1f))).reason)
        assertEquals("energy_mismatch", assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(inc = features().copy(rms = 0.03f))).reason)
    }

    @Test
    fun `measured silence trims boundaries without inventing a beat`() {
        val result = assertIs<AdaptiveTransitionDecision.SilenceTrim>(decide(
            features().copy(trailingSilenceMs = 800), features().copy(leadingSilenceMs = 400),
        ))
        assertEquals(9200L, result.outgoingContentEndMs)
        assertEquals(400L, result.incomingStartMs)
        assertNull(AdaptiveTransitionPlanner.toCrossfadePlan(result, 10000))
    }
    @Test
    fun `bounded candidate with smoother boundary wins without a long skip`() {
        val outgoing = features().copy(
            rhythmicStability = 0.1f,
            energyEnvelope = listOf(EnergyPoint(10000, 0.9f)),
            candidates = listOf(TransitionCandidate(7000, 0.1f), TransitionCandidate(9500, 0.1f)),
        )
        val incoming = features().copy(rms = 0.1f, candidates = listOf(TransitionCandidate(4000, 0.9f)))
        val result = assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(outgoing, incoming))
        assertEquals(9500L, result.outgoingContentEndMs)
        assertEquals(0L, result.incomingStartMs)
        val plan = assertNotNull(AdaptiveTransitionPlanner.toCrossfadePlan(result, 10000))
        assertEquals(9500L, plan.handoffMs)
    }

    @Test
    fun `no candidates retain original boundaries and equal energy does not justify skipping`() {
        val unknownRhythm = features().copy(rhythmicStability = null)
        val unchanged = assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(unknownRhythm, features()))
        assertEquals(10000L, unchanged.outgoingContentEndMs)
        assertEquals(0L, unchanged.incomingStartMs)
        val candidates = unknownRhythm.copy(candidates = listOf(TransitionCandidate(8500, 0.15f)))
        assertEquals(10000L, assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(candidates)).outgoingContentEndMs)
    }

    @Test
    fun `incomplete scans never trim to a partial decoded endpoint`() {
        val partial = features().copy(windowEndMs = 5000, trailingSilenceMs = 1000, unknownReason = "incomplete_window")
        val decision = assertIs<AdaptiveTransitionDecision.ShortCrossfade>(decide(partial))
        assertEquals("unknown_features", decision.reason)
        assertEquals(10000L, decision.outgoingContentEndMs)
        assertEquals(0L, decision.incomingStartMs)
    }

}
