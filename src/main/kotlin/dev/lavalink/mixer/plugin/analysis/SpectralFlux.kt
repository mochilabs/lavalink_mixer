package dev.lavalink.mixer.plugin

import kotlin.math.ln
import kotlin.math.sqrt

data class FluxWeights(
    val low: Float = 0.40f,
    val mid: Float = 0.40f,
    val high: Float = 0.20f,
)

/**
 * 3-Band Spectral Flux analyzer using [RealFft] (N=1024, Hann window, ~10ms hop).
 * To prevent stereo phase cancellation from time-domain downmixing, each channel
 * is transformed independently and log-magnitudes are averaged across channels.
 *
 * Bands (dynamically scaled by sample rate):
 *   Low:  30 ~ 150 Hz   - Kick drum, sub/bass transients
 *   Mid:  150 ~ 2000 Hz - Snare, clap, vocal/synth attacks
 *   High: 2000 ~ 10000 Hz - Hi-hats, cymbals, shakers
 */
object SpectralFluxAnalyzer {
    private val log = org.slf4j.LoggerFactory.getLogger(SpectralFluxAnalyzer::class.java)
    private const val FFT_SIZE = 1024

    private val fft = RealFft(FFT_SIZE)

    fun computeSpectralFlux(
        frames: List<Array<FloatArray>>,
        sampleRate: Int = 48000,
        weights: FluxWeights = FluxWeights(),
    ): FloatArray? {
        if (frames.isEmpty()) return null
        val totalSamples = frames.sumOf { it[0].size.toLong() }.toInt()
        if (totalSamples < FFT_SIZE) {
            log.debug("Frames too short for spectral flux: totalSamples=$totalSamples < FFT_SIZE=$FFT_SIZE")
            return null
        }

        val channels = frames[0].size
        if (channels == 0) return null

        val channelPcm = Array(channels) { FloatArray(totalSamples) }
        var offset = 0
        for (f in frames) {
            val len = f[0].size
            val chCount = minOf(channels, f.size)
            for (c in 0 until chCount) {
                System.arraycopy(f[c], 0, channelPcm[c], offset, len)
            }
            offset += len
        }

        val hopSize = (sampleRate / 100).coerceAtLeast(1)
        val centerOffset = (FFT_SIZE - hopSize) / 2
        val nHops = (totalSamples + hopSize - 1) / hopSize
        if (nHops <= 0) return null

        val lowStart = kotlin.math.round(30.0 * FFT_SIZE / sampleRate).toInt().coerceAtLeast(1)
        val lowEnd = kotlin.math.round(150.0 * FFT_SIZE / sampleRate).toInt().coerceAtLeast(lowStart)
        val midStart = lowEnd + 1
        val midEnd = kotlin.math.round(2000.0 * FFT_SIZE / sampleRate).toInt().coerceAtLeast(midStart)
        val highStart = midEnd + 1
        val highEnd = kotlin.math.round(10000.0 * FFT_SIZE / sampleRate).toInt().coerceIn(highStart, FFT_SIZE / 2 - 1)

        val lowFlux = FloatArray(nHops)
        val midFlux = FloatArray(nHops)
        val highFlux = FloatArray(nHops)

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val chMags = FloatArray(FFT_SIZE / 2)
        val currLogAvg = FloatArray(FFT_SIZE / 2)
        val prevLogAvg = FloatArray(FFT_SIZE / 2)
        val hann = fft.hannWindow

        for (h in 0 until nHops) {
            val start = h * hopSize - centerOffset
            java.util.Arrays.fill(currLogAvg, 0f)

            for (c in 0 until channels) {
                val pcm = channelPcm[c]
                for (i in 0 until FFT_SIZE) {
                    val idx = start + i
                    re[i] = if (idx in 0 until totalSamples) pcm[idx] * hann[i] else 0f
                    im[i] = 0f
                }

                fft.transform(re, im)
                fft.magnitudes(re, im, chMags)

                for (i in chMags.indices) {
                    currLogAvg[i] += ln(1f + 10f * chMags[i]) / channels
                }
            }

            if (h > 0) {
                var low = 0f
                for (k in lowStart..lowEnd) {
                    val d = currLogAvg[k] - prevLogAvg[k]
                    if (d > 0f) low += d
                }
                lowFlux[h] = low

                var mid = 0f
                for (k in midStart..midEnd) {
                    val d = currLogAvg[k] - prevLogAvg[k]
                    if (d > 0f) mid += d
                }
                midFlux[h] = mid

                var high = 0f
                for (k in highStart..highEnd) {
                    val d = currLogAvg[k] - prevLogAvg[k]
                    if (d > 0f) high += d
                }
                highFlux[h] = high
            } else {
                lowFlux[0] = 0f
                midFlux[0] = 0f
                highFlux[0] = 0f
            }

            System.arraycopy(currLogAvg, 0, prevLogAvg, 0, currLogAvg.size)
        }

        // Standardize each band by its standard deviation so low bass doesn't bury high attacks
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
