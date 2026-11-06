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
    fun toExtended(sampleRate: Int = 48000): ExtendedBeatGrid = ExtendedBeatGrid.fromLegacy(this, sampleRate)
}

/**
 * Sample-accurate beat grid with explicit metric downbeats, phrase confidence,
 * and floating-point period to prevent cumulative beat drift across multi-minute tracks.
 */
data class ExtendedBeatGrid(
    val bpm: Float,
    val phaseSample: Long,
    val bpmConfidence: Float,
    val downbeatSample: Long = phaseSample,
    val downbeatConfidence: Float = 0f,
    val phraseConfidence: Float = 0f,
    val isNon44Meter: Boolean = false,
    val sampleRate: Int = 48000,
) {
    val periodSamples: Double get() = (sampleRate.toDouble() * 60.0) / bpm.toDouble()
    val periodMs: Double get() = 60_000.0 / bpm.toDouble()
    val phaseMs: Long get() = Math.round(phaseSample.toDouble() * 1000.0 / sampleRate.toDouble())
    val downbeatMs: Long get() = Math.round(downbeatSample.toDouble() * 1000.0 / sampleRate.toDouble())

    fun beatSample(k: Long): Long = Math.round(phaseSample.toDouble() + k.toDouble() * periodSamples)
    fun barSample(barIndex: Long): Long = Math.round(downbeatSample.toDouble() + barIndex.toDouble() * (periodSamples * 4.0))

    fun toLegacyBeatGrid(): BeatGrid = BeatGrid(bpm, phaseMs, bpmConfidence)

    companion object {
        fun fromLegacy(grid: BeatGrid, sampleRate: Int = 48000): ExtendedBeatGrid {
            val phaseSample = Math.round(grid.phaseMs.toDouble() * sampleRate.toDouble() / 1000.0)
            return ExtendedBeatGrid(
                bpm = grid.bpm,
                phaseSample = phaseSample,
                bpmConfidence = grid.confidence,
                downbeatSample = phaseSample,
                downbeatConfidence = 0f,
                phraseConfidence = 0f,
                sampleRate = sampleRate,
            )
        }
    }
}