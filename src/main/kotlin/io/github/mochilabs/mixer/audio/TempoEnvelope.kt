package io.github.mochilabs.mixer

/** Tempo as a function of output samples per channel, including decoder prefill. */
data class TempoEnvelope(val initialRate: Double, val holdOutputSamples: Long, val recoveryOutputSamples: Long) {
    init {
        require(initialRate.isFinite() && initialRate in 0.5..2.0)
        require(holdOutputSamples >= 0 && recoveryOutputSamples >= 0)
    }

    fun rateAt(outputSample: Double): Double {
        if (outputSample < holdOutputSamples) return initialRate
        if (recoveryOutputSamples == 0L) return 1.0
        val x = ((outputSample - holdOutputSamples) / recoveryOutputSamples).coerceIn(0.0, 1.0)
        val smooth = x * x * (3.0 - 2.0 * x)
        return initialRate + (1.0 - initialRate) * smooth
    }
}
