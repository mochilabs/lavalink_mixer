package io.github.mochilabs.mixer

/**
 * Beat grid for a decoded region: a regular pulse the mixer can snap a
 * crossfade to. Distinct from a naive tempo float — carries the phase so the
 * mixer knows WHERE beats land, and the confidence so the gate can tell a real
 * pulse apart from an autocorrelation that merely picked *some* lag.
 */
data class BeatGrid(
    val bpm: Float,
    val phaseMs: Long,
    val confidence: Float,
) {
    val periodMs: Double get() = 60_000.0 / bpm
}