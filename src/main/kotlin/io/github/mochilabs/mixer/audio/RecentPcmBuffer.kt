package io.github.mochilabs.mixer

/**
 * Pre-allocated stereo ring holding the most recent main PCM, used to pre-roll
 * the crossover when a transition recipe activates. Zero heap allocations while
 * processing.
 */
class RecentPcmBuffer(val capacity: Int) {
    private val bufferL = FloatArray(capacity)
    private val bufferR = FloatArray(capacity)
    private var writeHead = 0

    fun reset() {
        bufferL.fill(0f)
        bufferR.fill(0f)
        writeHead = 0
    }

    fun write(inputL: FloatArray, inputR: FloatArray, offset: Int = 0, length: Int = inputL.size) {
        val cap = capacity
        var pos = writeHead
        val end = offset + length
        for (i in offset until end) {
            bufferL[pos] = inputL[i]
            bufferR[pos] = inputR[i]
            pos++
            if (pos >= cap) pos = 0
        }
        writeHead = pos
    }

    /** Copies the most recent [length] written frames into [outL]/[outR], oldest first. */
    fun copyRecent(outL: FloatArray, outR: FloatArray, length: Int) {
        val n = length.coerceIn(0, capacity)
        var pos = writeHead - n
        if (pos < 0) pos += capacity
        for (i in 0 until n) {
            outL[i] = bufferL[pos]
            outR[i] = bufferR[pos]
            pos++
            if (pos >= capacity) pos = 0
        }
    }
}
