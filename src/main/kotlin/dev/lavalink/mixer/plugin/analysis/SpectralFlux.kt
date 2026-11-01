package dev.lavalink.mixer.plugin

import kotlin.math.sqrt

data class FluxWeights(
    val low: Float = 0.50f,
    val mid: Float = 0.35f,
    val high: Float = 0.15f,
)

/**
 * 3-Band Spectral Flux analyzer using [RealFft] (N=2048, Hann window, 10ms hop @ 48kHz).
 * Bands:
 *   Low:  30 ~ 150 Hz   (Bins 2..6)   - Kick drum, sub/bass transients
 *   Mid:  150 ~ 2000 Hz (Bins 7..85)  - Snare, clap, vocal/synth attacks
 *   High: 2000 ~ 10000 Hz (Bins 86..426) - Hi-hats, cymbals, shakers
 */
object SpectralFluxAnalyzer {
    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = 480
    private const val LOW_BIN_START = 2
    private const val LOW_BIN_END = 6
    private const val MID_BIN_START = 7
    private const val MID_BIN_END = 85
    private const val HIGH_BIN_START = 86
    private const val HIGH_BIN_END = 426

    private val fft = RealFft(FFT_SIZE)

    fun computeSpectralFlux(
        frames: List<Array<FloatArray>>,
        weights: FluxWeights = FluxWeights(),
    ): FloatArray? {
        if (frames.isEmpty()) return null
        val totalSamples = frames.sumOf { it[0].size.toLong() }.toInt()
        if (totalSamples < FFT_SIZE) return null

        // Downmix to mono
        val mono = FloatArray(totalSamples)
        var offset = 0
        for (f in frames) {
            val len = f[0].size
            val channels = f.size
            for (i in 0 until len) {
                var s = 0f
                for (c in 0 until channels) s += f[c][i]
                mono[offset++] = s / channels
            }
        }

        val centerOffset = (FFT_SIZE - HOP_SIZE) / 2
        val nHops = (totalSamples + HOP_SIZE - 1) / HOP_SIZE
        if (nHops <= 0) return null

        val lowFlux = FloatArray(nHops)
        val midFlux = FloatArray(nHops)
        val highFlux = FloatArray(nHops)

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val currMags = FloatArray(FFT_SIZE / 2)
        val prevMags = FloatArray(FFT_SIZE / 2)
        val hann = fft.hannWindow

        for (h in 0 until nHops) {
            val start = h * HOP_SIZE - centerOffset
            for (i in 0 until FFT_SIZE) {
                val idx = start + i
                val s = if (idx in 0 until totalSamples) mono[idx] else 0f
                re[i] = s * hann[i]
                im[i] = 0f
            }

            fft.transform(re, im)
            fft.magnitudes(re, im, currMags)

            if (h > 0) {
                var low = 0f
                for (k in LOW_BIN_START..LOW_BIN_END) {
                    val d = currMags[k] - prevMags[k]
                    if (d > 0f) low += d
                }
                lowFlux[h] = low

                var mid = 0f
                for (k in MID_BIN_START..MID_BIN_END) {
                    val d = currMags[k] - prevMags[k]
                    if (d > 0f) mid += d
                }
                midFlux[h] = mid

                var high = 0f
                for (k in HIGH_BIN_START..HIGH_BIN_END) {
                    val d = currMags[k] - prevMags[k]
                    if (d > 0f) high += d
                }
                highFlux[h] = high
            } else {
                var low = 0f
                for (k in LOW_BIN_START..LOW_BIN_END) low += currMags[k]
                lowFlux[0] = low

                var mid = 0f
                for (k in MID_BIN_START..MID_BIN_END) mid += currMags[k]
                midFlux[0] = mid

                var high = 0f
                for (k in HIGH_BIN_START..HIGH_BIN_END) high += currMags[k]
                highFlux[0] = high
            }

            System.arraycopy(currMags, 0, prevMags, 0, currMags.size)
        }

        // Standardize each band by its standard deviation
        val lowStd = stdDev(lowFlux)
        val midStd = stdDev(midFlux)
        val highStd = stdDev(highFlux)

        val combined = FloatArray(nHops)
        val invLow = if (lowStd > 1e-6f) weights.low / lowStd else 0f
        val invMid = if (midStd > 1e-6f) weights.mid / midStd else 0f
        val invHigh = if (highStd > 1e-6f) weights.high / highStd else 0f

        for (i in 0 until nHops) {
            combined[i] = lowFlux[i] * invLow + midFlux[i] * invMid + highFlux[i] * invHigh
        }
        return combined
    }

    private fun stdDev(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var sum = 0.0
        for (v in a) sum += v
        val mean = sum / a.size
        var varAcc = 0.0
        for (v in a) {
            val d = v - mean
            varAcc += d * d
        }
        return sqrt(varAcc / a.size).toFloat()
    }
}
