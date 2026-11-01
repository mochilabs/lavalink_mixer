package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatMatchPolicyTest {

    @Test
    fun `recovery is reserved inside budget and begins after beat overlap`() {
        val decision = assertIs<TransitionDecision.BeatMatched>(BeatMatchPolicy.decide(
            grid(120f), grid(115f), 60_000, 5000,
        ))
        val plan = assertNotNull(BeatMatchPolicy.toCrossfadePlan(decision, 60_000, 5000))
        assertTrue(plan.recoveryMs >= 500)
        assertEquals(plan.durationMs, plan.overlapMs + plan.recoveryMs)
        assertEquals(plan.handoffMs, plan.startMs + plan.durationMs)
        assertTrue(plan.durationMs <= 5000)
        assertTrue(plan.startMs + plan.overlapMs < plan.handoffMs)
    }

    @Test
    fun `insufficient recovery space falls back rather than abrupt tempo reset`() {
        assertIs<TransitionDecision.EqualPower>(BeatMatchPolicy.decide(grid(120f), grid(115f), 60_000, 1100))
    }

    private fun grid(bpm: Float = 120f, phaseMs: Long = 0L, confidence: Float = 0.8f) =
        BeatGrid(bpm = bpm, phaseMs = phaseMs, confidence = confidence)

    @Test
    fun `crossfade disabled produces Gapless`() {
        val out = grid()
        val inc = grid()

        val r1 = BeatMatchPolicy.decide(out, inc, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, crossfadeEnabled = false)
        assertIs<TransitionDecision.Gapless>(r1)
        assertEquals("crossfade_disabled", r1.fallbackReason)

        val r2 = BeatMatchPolicy.decide(out, inc, contentEndMs = 0L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.Gapless>(r2)
        assertEquals("crossfade_disabled", r2.fallbackReason)

        val r3 = BeatMatchPolicy.decide(out, inc, contentEndMs = 50_000L, requestedCrossfadeMs = 0L)
        assertIs<TransitionDecision.Gapless>(r3)
        assertEquals("crossfade_disabled", r3.fallbackReason)
    }

    @Test
    fun `beatmatch disabled produces EqualPower`() {
        val r = BeatMatchPolicy.decide(grid(), grid(), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, beatmatchEnabled = false)
        assertIs<TransitionDecision.EqualPower>(r)
        assertEquals("beatmatch_disabled", r.fallbackReason)
    }

    @Test
    fun `missing grids produce EqualPower`() {
        val r1 = BeatMatchPolicy.decide(null, grid(), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r1)
        assertEquals("missing_outgoing_grid", r1.fallbackReason)

        val r2 = BeatMatchPolicy.decide(grid(), null, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r2)
        assertEquals("missing_incoming_grid", r2.fallbackReason)
    }

    @Test
    fun `invalid bpm produces EqualPower`() {
        val r1 = BeatMatchPolicy.decide(grid(bpm = 0f), grid(), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r1)
        assertEquals("invalid_bpm", r1.fallbackReason)

        val r2 = BeatMatchPolicy.decide(grid(), grid(bpm = -10f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r2)
        assertEquals("invalid_bpm", r2.fallbackReason)
    }

    @Test
    fun `low confidence produces EqualPower`() {
        val r1 = BeatMatchPolicy.decide(grid(confidence = 0.25f), grid(confidence = 0.8f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r1)
        assertEquals("low_confidence", r1.fallbackReason)

        val r2 = BeatMatchPolicy.decide(grid(confidence = 0.8f), grid(confidence = 0.29f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r2)
        assertEquals("low_confidence", r2.fallbackReason)

        val r3 = BeatMatchPolicy.decide(grid(confidence = 0.45f), grid(confidence = 0.45f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, minConfidence = 0.50f)
        assertIs<TransitionDecision.EqualPower>(r3)
        assertEquals("low_confidence", r3.fallbackReason)
    }

    @Test
    fun `tempo difference exceeded produces EqualPower`() {
        // 120 vs 140 -> rateRatio ~ 0.857 (|1 - 0.857| = 0.143 > 0.06)
        val r1 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 140f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r1)
        assertEquals("tempo_diff_exceeded", r1.fallbackReason)

        // 120 vs 125 -> rateRatio = 0.96 (|1 - 0.96| = 0.04 <= 0.06) -> succeeds
        val r2 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 125f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.BeatMatched>(r2)
    }

    @Test
    fun `fade window too short produces EqualPower`() {
        // 120 bpm -> period 500ms. 2 beats = 1000ms.
        // Requested 800ms -> none of (8, 4, 2) beats fit.
        val r1 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 120f), contentEndMs = 50_000L, requestedCrossfadeMs = 800L)
        assertIs<TransitionDecision.EqualPower>(r1)
        assertEquals("fade_window_too_short", r1.fallbackReason)

        // contentEndMs = 600ms -> none fit.
        val r2 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 120f), contentEndMs = 600L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.EqualPower>(r2)
        assertEquals("fade_window_too_short", r2.fallbackReason)
    }

    @Test
    fun `incoming track too short produces EqualPower`() {
        // incomingDuration = 3000ms.
        // phaseMs = 2600ms. rawIncomingStart = 2600ms >= 3000 - 500 (2500ms).
        val r = BeatMatchPolicy.decide(
            grid(bpm = 120f),
            grid(bpm = 120f, phaseMs = 2600L),
            contentEndMs = 50_000L,
            requestedCrossfadeMs = 5_000L,
            incomingDurationMs = 3000L,
        )
        assertIs<TransitionDecision.EqualPower>(r)
        assertEquals("incoming_track_too_short", r.fallbackReason)
    }

    @Test
    fun `beat count selection prefers largest fitting power-of-two beats`() {
        // 120 bpm -> period 500ms.
        // 8 beats = 4000ms, 4 beats = 2000ms, 2 beats = 1000ms.

        val r8 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 120f), contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertIs<TransitionDecision.BeatMatched>(r8)
        assertEquals(8, r8.fadeBeats)
        assertEquals(4000L, r8.fadeMs)

        val r4 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 120f), contentEndMs = 50_000L, requestedCrossfadeMs = 3_000L)
        assertIs<TransitionDecision.BeatMatched>(r4)
        assertEquals(4, r4.fadeBeats)
        assertEquals(2000L, r4.fadeMs)

        val r2 = BeatMatchPolicy.decide(grid(bpm = 120f), grid(bpm = 120f), contentEndMs = 50_000L, requestedCrossfadeMs = 1_500L)
        assertIs<TransitionDecision.BeatMatched>(r2)
        assertEquals(2, r2.fadeBeats)
        assertEquals(1000L, r2.fadeMs)
    }

    @Test
    fun `handoff and start snapped to outgoing beat grid`() {
        // outgoing bpm = 120 -> period = 500ms. phase = 100ms.
        // contentEndMs = 50_200ms.
        // k = floor((50_200 - 100) / 500) = floor(50_100 / 500) = 100.
        // snappedHandoff = 100 + 100 * 500 = 50_100ms.
        // fadeBeats = 8, fadeMs = 4000ms.
        // startMs = 50_100 - 4000 = 46_100ms.
        // handoffMs = 46_100 + 4000 = 50_100ms.
        val r = BeatMatchPolicy.decide(
            grid(bpm = 120f, phaseMs = 100L),
            grid(bpm = 120f, phaseMs = 0L),
            contentEndMs = 50_200L,
            requestedCrossfadeMs = 5_000L,
        )
        assertIs<TransitionDecision.BeatMatched>(r)
        assertEquals(50_100L, r.handoffMs)
        assertEquals(46_100L, r.startMs)
        assertEquals(4000L, r.fadeMs)
    }

    @Test
    fun `incomingStartMs snaps to first beat at or after incomingLeadMs`() {
        val out = grid(bpm = 120f)
        val inc = grid(bpm = 120f, phaseMs = 200L) // period = 500ms

        // Case 1: leadMs = 0 <= phaseMs (200) -> 200ms
        val r1 = BeatMatchPolicy.decide(out, inc, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, incomingLeadMs = 0L)
        assertIs<TransitionDecision.BeatMatched>(r1)
        assertEquals(200L, r1.incomingStartMs)

        // Case 2: leadMs = 150 <= phaseMs (200) -> 200ms
        val r2 = BeatMatchPolicy.decide(out, inc, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, incomingLeadMs = 150L)
        assertIs<TransitionDecision.BeatMatched>(r2)
        assertEquals(200L, r2.incomingStartMs)

        // Case 3: leadMs = 300 > phaseMs (200) -> ceil((300 - 200)/500) = 1 -> 200 + 500 = 700ms
        val r3 = BeatMatchPolicy.decide(out, inc, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, incomingLeadMs = 300L)
        assertIs<TransitionDecision.BeatMatched>(r3)
        assertEquals(700L, r3.incomingStartMs)

        // Case 4: leadMs = 1200 > phaseMs (200) -> ceil((1200 - 200)/500) = 2 -> 200 + 1000 = 1200ms
        val r4 = BeatMatchPolicy.decide(out, inc, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L, incomingLeadMs = 1200L)
        assertIs<TransitionDecision.BeatMatched>(r4)
        assertEquals(1200L, r4.incomingStartMs)
    }

    @Test
    fun `toCrossfadePlan maps decisions correctly`() {
        // BeatMatched
        val matchDec = TransitionDecision.BeatMatched(
            fadeBeats = 8,
            fadeMs = 4000L,
            targetBpm = 120f,
            rateRatio = 0.98f,
            startMs = 46_000L,
            handoffMs = 50_000L,
            incomingStartMs = 250L,
        )
        val p1 = BeatMatchPolicy.toCrossfadePlan(matchDec, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertNotNull(p1)
        assertTrue(p1.beatMatched)
        assertEquals(46_000L, p1.startMs)
        assertEquals(50_000L, p1.handoffMs)
        assertEquals(4000L, p1.durationMs)
        assertEquals(0.98f, p1.rateRatio)
        assertEquals(250L, p1.incomingStartMs)
        assertEquals(8, p1.fadeBeats)
        assertEquals(120f, p1.targetBpm)
        assertNull(p1.fallbackReason)

        // EqualPower
        val eqDec = TransitionDecision.EqualPower("tempo_diff_exceeded")
        val p2 = BeatMatchPolicy.toCrossfadePlan(eqDec, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertNotNull(p2)
        assertEquals(false, p2.beatMatched)
        assertEquals("tempo_diff_exceeded", p2.fallbackReason)
        assertEquals(50_000L - 5000L, p2.startMs)
        assertEquals(50_000L, p2.handoffMs)
        assertEquals(5000L, p2.durationMs)

        // Gapless
        val gapDec = TransitionDecision.Gapless("crossfade_disabled")
        val p3 = BeatMatchPolicy.toCrossfadePlan(gapDec, contentEndMs = 50_000L, requestedCrossfadeMs = 5_000L)
        assertNull(p3)
    }
}
