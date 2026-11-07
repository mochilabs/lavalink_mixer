package io.github.mochilabs.mixer

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks recent transition strategies per guild to prevent mechanical repetition
 * and introduce musical transition variation.
 */
class TransitionMemory(val historySize: Int = 5) {
    private val histories = ConcurrentHashMap<Long, ArrayDeque<TransitionStrategy>>()

    fun record(guildId: Long, strategy: TransitionStrategy) {
        val deque = histories.computeIfAbsent(guildId) { ArrayDeque() }
        synchronized(deque) {
            deque.addFirst(strategy)
            while (deque.size > historySize) {
                deque.removeLast()
            }
        }
    }

    fun getHistory(guildId: Long): List<TransitionStrategy> {
        val deque = histories[guildId] ?: return emptyList()
        synchronized(deque) {
            return deque.toList()
        }
    }

    /**
     * Multiplicative penalty weight [0.0..1.0] for a candidate strategy.
     * 1st prior use: 0.25x
     * 2nd prior use: 0.50x
     * 3rd prior use: 0.75x
     * 4th+ or never used: 1.0x
     */
    fun repetitionWeight(guildId: Long, candidate: TransitionStrategy): Float {
        val history = getHistory(guildId)
        val idx = history.indexOf(candidate)
        return when (idx) {
            0 -> 0.25f // Used in immediate previous transition
            1 -> 0.50f // Used 2 transitions ago
            2 -> 0.75f // Used 3 transitions ago
            else -> 1.00f // Not in recent history
        }
    }

    /**
     * Picks the best `(strategy, baseScore)` candidate after repetition penalties.
     *
     * A non-zero [trackPairSeed] adds a deterministic bonus in `[0, SEED_JITTER)` per
     * strategy, so candidates whose penalized scores lie closer than [SEED_JITTER]
     * resolve differently for different track pairs. Planner base scores differ by at
     * least 0.15, so without history the seed never overrides the primary choice.
     */
    fun rank(
        guildId: Long,
        candidates: List<Pair<TransitionStrategy, Float>>,
        trackPairSeed: Int = 0,
    ): TransitionStrategy {
        val primary = candidates.first().first
        if (candidates.size <= 1) return primary
        return candidates.maxByOrNull { (strategy, baseScore) ->
            baseScore * repetitionWeight(guildId, strategy) + seedJitter(trackPairSeed, strategy)
        }?.first ?: primary
    }

    private fun seedJitter(seed: Int, strategy: TransitionStrategy): Float {
        if (seed == 0) return 0f
        // lowbias32 finalizer: spreads adjacent seeds and ordinals across the full range.
        var h = seed xor ((strategy.ordinal + 1) * -0x61c88647)
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return (h ushr 8).toFloat() / (1 shl 24).toFloat() * SEED_JITTER
    }

    fun clear(guildId: Long) {
        histories.remove(guildId)
    }

    companion object {
        /** Upper bound of the track-pair tie-break bonus; below the 0.15 planner score gap. */
        const val SEED_JITTER = 0.10f
        val global = TransitionMemory()
    }
}
