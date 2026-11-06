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

    fun clear(guildId: Long) {
        histories.remove(guildId)
    }

    companion object {
        val global = TransitionMemory()
    }
}
