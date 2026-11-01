package dev.lavalink.mixer.plugin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin in-place Radix-2 Real/Complex Fast Fourier Transform for fixed size N=2048.
 * Zero heap allocations during [transform] and [magnitudes].
 * Thread-safe for distinct instances or when using per-call scratch buffers.
 */
class RealFft(val size: Int = 2048) {
    init {
        require(size > 0 && (size and (size - 1)) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val log2N = Integer.numberOfTrailingZeros(size)
    private val bitRev = IntArray(size) { i ->
        Integer.reverse(i) ushr (32 - log2N)
    }

    private val cosTable = FloatArray(size / 2) { k ->
        cos(-2.0 * PI * k / size).toFloat()
    }
    private val sinTable = FloatArray(size / 2) { k ->
        sin(-2.0 * PI * k / size).toFloat()
    }

    /** Precomputed Hann window of length [size]. */
    val hannWindow = FloatArray(size) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / (size - 1)))).toFloat()
    }

    /**
     * In-place Radix-2 Cooley-Tukey FFT.
     * [re] and [im] must be arrays of length [size].
     */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = size
        for (i in 0 until n) {
            val j = bitRev[i]
            if (i < j) {
                val tempRe = re[i]
                re[i] = re[j]
                re[j] = tempRe

                val tempIm = im[i]
                im[i] = im[j]
                im[j] = tempIm
            }
        }

        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var k = 0
            while (k < n) {
                for (j in 0 until half) {
                    val twiddleIdx = j * step
                    val cos = cosTable[twiddleIdx]
                    val sin = sinTable[twiddleIdx]

                    val vRe = re[k + j + half] * cos - im[k + j + half] * sin
                    val vIm = re[k + j + half] * sin + im[k + j + half] * cos

                    val uRe = re[k + j]
                    val uIm = im[k + j]

                    re[k + j] = uRe + vRe
                    im[k + j] = uIm + vIm
                    re[k + j + half] = uRe - vRe
                    im[k + j + half] = uIm - vIm
                }
                k += len
            }
            len = len shl 1
        }
    }

    /**
     * Computes the magnitude of bins 0 until size / 2 into [outMags].
     * [outMags] must have length at least [size] / 2.
     */
    fun magnitudes(re: FloatArray, im: FloatArray, outMags: FloatArray) {
        val half = size / 2
        for (i in 0 until half) {
            outMags[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
    }
}
