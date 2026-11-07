package io.github.mochilabs.mixer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircularDelayBufferTest {
    @Test
    fun `circular buffer delays impulse and decays with feedback`() {
        val capacity = 96000 // 2.0s @ 48kHz
        val buffer = CircularDelayBuffer(capacity = capacity)

        val delaySamples = 12000 // 250ms
        val input = FloatArray(delaySamples)
        input[0] = 1.0f // Impulse at t = 0

        // Write impulse and first delay period (dry feed)
        buffer.write(input, input, 0, delaySamples)

        val outL = FloatArray(delaySamples)
        val outR = FloatArray(delaySamples)
        // First read at delaySamples: should produce the delayed impulse with feedback
        buffer.readAndMix(
            delaySamples = delaySamples,
            feedback = 0.5f,
            outputL = outL,
            outputR = outR,
            offset = 0,
            length = delaySamples,
            terminationGain = 1.0f,
        )

        assertEquals(1.0f, outL[0], 0.01f, "delayed impulse must appear at offset 0 of the delayed read")
        for (i in 1 until delaySamples) {
            assertEquals(0.0f, outL[i], 1e-4f)
        }

        // Second bounce: should produce impulse scaled by feedback (0.5f)
        val b2L = FloatArray(delaySamples)
        val b2R = FloatArray(delaySamples)
        buffer.readAndMix(
            delaySamples = delaySamples,
            feedback = 0.5f,
            outputL = b2L,
            outputR = b2R,
            offset = 0,
            length = delaySamples,
            terminationGain = 1.0f,
        )
        assertEquals(0.5f, b2L[0], 0.01f, "second bounce must have gain 0.5")

        // Third bounce: terminationGain = 0.0f forces output to 0.0f
        val b3L = FloatArray(delaySamples)
        val b3R = FloatArray(delaySamples)
        buffer.readAndMix(
            delaySamples = delaySamples,
            feedback = 0.5f,
            outputL = b3L,
            outputR = b3R,
            offset = 0,
            length = delaySamples,
            terminationGain = 0.0f,
        )
        assertEquals(0.0f, b3L[0], 1e-4f, "termination gain 0.0 must force output to 0.0")
    }

    @Test
    fun `copyRecent returns the latest frames oldest first across the wrap point`() {
        val buffer = CircularDelayBuffer(capacity = 8)
        val left = FloatArray(11) { it.toFloat() }
        val right = FloatArray(11) { -it.toFloat() }
        buffer.write(left, right, 0, 11) // write head wraps past capacity

        val outL = FloatArray(5)
        val outR = FloatArray(5)
        buffer.copyRecent(outL, outR, 5)

        assertTrue(floatArrayOf(6f, 7f, 8f, 9f, 10f).contentEquals(outL), "got ${outL.toList()}")
        assertTrue(floatArrayOf(-6f, -7f, -8f, -9f, -10f).contentEquals(outR), "got ${outR.toList()}")
    }
}
