package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionMemoryTest {
    @Test
    fun `records recent transitions and penalizes consecutive repetitions`() {
        val memory = TransitionMemory(historySize = 5)
        val guildId = 4001L

        // Initially no history
        assertTrue(memory.getHistory(guildId).isEmpty())
        assertEquals(1.0f, memory.repetitionWeight(guildId, TransitionStrategy.PHRASE_MATCHED_BASS_SWAP))

        // Record 1st transition
        memory.record(guildId, TransitionStrategy.PHRASE_MATCHED_BASS_SWAP)
        assertEquals(listOf(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP), memory.getHistory(guildId))

        // Immediately repeating candidate gets heavy penalty (0.25x weight)
        val repeatWeight = memory.repetitionWeight(guildId, TransitionStrategy.PHRASE_MATCHED_BASS_SWAP)
        assertTrue(repeatWeight <= 0.3f, "immediate repeat must be penalized (got $repeatWeight)")

        // Alternative candidate gets full weight (1.0f)
        val altWeight = memory.repetitionWeight(guildId, TransitionStrategy.SHORT_BASS_SWAP)
        assertEquals(1.0f, altWeight, "alternative strategy must have full weight")

        // Record 2nd and 3rd transitions
        memory.record(guildId, TransitionStrategy.SHORT_BASS_SWAP)
        memory.record(guildId, TransitionStrategy.BEATMATCHED_BLEND)

        // After 2 intervening transitions, the first strategy's penalty diminishes
        val agedWeight = memory.repetitionWeight(guildId, TransitionStrategy.PHRASE_MATCHED_BASS_SWAP)
        assertTrue(agedWeight >= 0.7f, "aged strategy weight should recover towards 1.0 (got $agedWeight)")
    }

    @Test
    fun `planner shifts away from recently repeated strategy when TransitionMemory is provided`() {
        val memory = TransitionMemory(historySize = 5)
        val guildId = 4002L
        val grid = ExtendedBeatGrid(bpm = 128f, phaseSample = 0L, bpmConfidence = 0.9f, downbeatConfidence = 0.8f, phraseConfidence = 0.7f)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        val first = AdaptiveTransitionPlanner.plan(grid, grid, features, features, 8000L, 60000L, guildId = guildId, memory = memory)
        assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, first.strategy)
        memory.record(guildId, first.strategy)

        val second = AdaptiveTransitionPlanner.plan(grid, grid, features, features, 8000L, 60000L, guildId = guildId, memory = memory)
        assertEquals(TransitionStrategy.SHORT_BASS_SWAP, second.strategy)
    }

    @Test
    fun `track pair seed never overrides a clear planner preference`() {
        val memory = TransitionMemory(historySize = 5)
        val candidates = listOf(
            TransitionStrategy.PHRASE_MATCHED_BASS_SWAP to 1.0f,
            TransitionStrategy.SHORT_BASS_SWAP to 0.85f,
            TransitionStrategy.BEATMATCHED_BLEND to 0.70f,
        )
        for (seed in 1..500) {
            assertEquals(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, memory.rank(4003L, candidates, seed), "seed=$seed")
        }
    }

    @Test
    fun `track pair seed resolves near ties differently across track pairs`() {
        val memory = TransitionMemory(historySize = 5)
        val guildId = 4004L
        // PHRASE used three transitions ago: 1.0 * 0.75 = 0.75 vs SHORT 0.78, a near tie.
        memory.record(guildId, TransitionStrategy.PHRASE_MATCHED_BASS_SWAP)
        memory.record(guildId, TransitionStrategy.BEATMATCHED_BLEND)
        memory.record(guildId, TransitionStrategy.DOWNBEAT_CUT)
        val candidates = listOf(
            TransitionStrategy.PHRASE_MATCHED_BASS_SWAP to 1.0f,
            TransitionStrategy.SHORT_BASS_SWAP to 0.78f,
        )
        assertEquals(TransitionStrategy.SHORT_BASS_SWAP, memory.rank(guildId, candidates, 0))
        val picks = (1..200).map { memory.rank(guildId, candidates, ("out-$it" to "in-$it").hashCode()) }.toSet()
        assertEquals(setOf(TransitionStrategy.PHRASE_MATCHED_BASS_SWAP, TransitionStrategy.SHORT_BASS_SWAP), picks)
    }
}
