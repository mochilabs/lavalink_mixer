package io.github.mochilabs.mixer.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct Form II Transposed biquad filter.
 * Zero memory allocation in [process].
 */
class BiquadFilter {
    var b0: Float = 1.0f
    var b1: Float = 0.0f
    var b2: Float = 0.0f
    var a1: Float = 0.0f
    var a2: Float = 0.0f

    var z1: Float = 0.0f
    var z2: Float = 0.0f

    fun reset() {
        z1 = 0.0f
        z2 = 0.0f
    }

    fun setLowPass(sampleRate: Int, cutoffHz: Float, q: Double = BUTTERWORTH_Q) {
        val omega = 2.0 * Math.PI * cutoffHz.toDouble() / sampleRate.toDouble()
        val cosW = cos(omega)
        val sinW = sin(omega)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha
        b0 = (((1.0 - cosW) / 2.0) / a0).toFloat()
        b1 = ((1.0 - cosW) / a0).toFloat()
        b2 = (((1.0 - cosW) / 2.0) / a0).toFloat()
        a1 = ((-2.0 * cosW) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun setHighPass(sampleRate: Int, cutoffHz: Float, q: Double = BUTTERWORTH_Q) {
        val omega = 2.0 * Math.PI * cutoffHz.toDouble() / sampleRate.toDouble()
        val cosW = cos(omega)
        val sinW = sin(omega)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha
        b0 = (((1.0 + cosW) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + cosW)) / a0).toFloat()
        b2 = (((1.0 + cosW) / 2.0) / a0).toFloat()
        a1 = ((-2.0 * cosW) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(input: FloatArray, output: FloatArray, offset: Int = 0, length: Int = input.size) {
        var localZ1 = z1
        var localZ2 = z2
        val localB0 = b0
        val localB1 = b1
        val localB2 = b2
        val localA1 = a1
        val localA2 = a2

        val end = offset + length
        for (i in offset until end) {
            val x = input[i]
            val y = localB0 * x + localZ1
            localZ1 = localB1 * x - localA1 * y + localZ2
            localZ2 = localB2 * x - localA2 * y
            output[i] = y
        }

        z1 = localZ1
        z2 = localZ2
    }

    companion object {
        val BUTTERWORTH_Q: Double = 1.0 / sqrt(2.0)
    }
}
