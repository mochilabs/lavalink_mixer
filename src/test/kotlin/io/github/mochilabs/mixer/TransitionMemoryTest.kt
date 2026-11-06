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
        memory.record(guildId, TransitionStrategy.ECHO_OUT)

        // After 2 intervening transitions, the first strategy's penalty diminishes
        val agedWeight = memory.repetitionWeight(guildId, TransitionStrategy.PHRASE_MATCHED_BASS_SWAP)
        assertTrue(agedWeight >= 0.7f, "aged strategy weight should recover towards 1.0 (got $agedWeight)")
    }
}
