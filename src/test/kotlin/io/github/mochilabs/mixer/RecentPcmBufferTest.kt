package io.github.mochilabs.mixer

import kotlin.test.Test
import kotlin.test.assertTrue

class RecentPcmBufferTest {
    @Test
    fun `copyRecent returns the latest frames oldest first across the wrap point`() {
        val buffer = RecentPcmBuffer(capacity = 8)
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
