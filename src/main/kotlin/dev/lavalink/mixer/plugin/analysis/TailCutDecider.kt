package dev.lavalink.mixer.plugin

/**
 * Realtime trailing-silence cut decision. The mixer filter reports per-chunk
 * peaks; once silence has persisted for [confirmMs] AND the position is
 * inside the tail window, the track may end early.
 *
 * Unlike pre-analysis this needs no extra decode pass, so it also covers
 * streams — at the price of hearing [confirmMs] of silence first.
 */
class TailCutDecider {
    @Volatile var silentSamples: Int = 0
    @Volatile var silenceStartSample: Long = -1L

    fun note(peak: Float, samples: Int, thresholdAmp: Float, endOfChunkSamples: Long = -1L) {
        if (peak >= thresholdAmp) {
            silentSamples = 0
            silenceStartSample = -1L
        } else {
            if (silentSamples == 0 && endOfChunkSamples >= 0L) {
                silenceStartSample = (endOfChunkSamples - samples).coerceAtLeast(0L)
            }
            silentSamples += samples
        }
    }

    fun reset() {
        silentSamples = 0
        silenceStartSample = -1L
    }

    fun sustainedMs(sampleRate: Int = 48000): Long = silentSamples * 1000L / sampleRate

    fun isConfirmedBefore(limitMs: Long, durationMs: Long, confirmMs: Long, tailWindowMs: Long, sampleRate: Int = 48000): Boolean {
        if (sustainedMs(sampleRate) < confirmMs) return false
        if (durationMs <= 0 || durationMs >= Long.MAX_VALUE / 2) return false
        val startSample = silenceStartSample
        if (startSample < 0L) return false
        val startMs = startSample * 1000L / sampleRate
        if (startMs < 5000 || startMs > limitMs) return false
        return durationMs - startMs <= tailWindowMs
    }

    fun shouldCut(posMs: Long, durationMs: Long, confirmMs: Long, tailWindowMs: Long, sampleRate: Int = 48000): Boolean {
        if (sustainedMs(sampleRate) < confirmMs) return false
        if (durationMs > 0 && durationMs < Long.MAX_VALUE / 2) {
            if (posMs < 5000) return false
            val startSample = silenceStartSample
            if (startSample >= 0L) {
                val startMs = startSample * 1000L / sampleRate
                if (startMs < 5000 || posMs < startMs) return false
                return durationMs - startMs <= tailWindowMs
            }
            return durationMs - posMs <= tailWindowMs
        }
        // Stream: no position reference — demand longer evidence.
        return sustainedMs(sampleRate) >= maxOf(confirmMs, 3000)
    }
}
