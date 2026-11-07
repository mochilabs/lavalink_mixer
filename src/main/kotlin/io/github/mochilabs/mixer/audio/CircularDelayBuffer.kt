package io.github.mochilabs.mixer

/**
 * Pre-allocated circular delay buffer for Echo Out and time-domain delay effects.
 * 96,000 samples @ 48kHz stereo (2.0 seconds capacity, accommodating down to BPM 40).
 * Zero heap allocations during real-time processing.
 */
class CircularDelayBuffer(val capacity: Int = 96000) {
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

    fun readAndMix(
        delaySamples: Int,
        feedback: Float,
        outputL: FloatArray,
        outputR: FloatArray,
        offset: Int = 0,
        length: Int = outputL.size,
        terminationGain: Float = 1.0f,
    ) {
        if (length <= 0) return
        val cap = capacity
        val clampedDelay = delaySamples.coerceIn(1, cap - 1)
        var readPos = (writeHead - clampedDelay)
        while (readPos < 0) readPos += cap

        var writePos = writeHead
        val end = offset + length

        for (i in offset until end) {
            val delayedL = bufferL[readPos]
            val delayedR = bufferR[readPos]

            // Feed delayed signal back into the buffer
            bufferL[writePos] = delayedL * feedback
            bufferR[writePos] = delayedR * feedback

            // Mix delayed signal into output
            outputL[i] += delayedL * terminationGain
            outputR[i] += delayedR * terminationGain

            readPos++
            if (readPos >= cap) readPos = 0

            writePos++
            if (writePos >= cap) writePos = 0
        }

        writeHead = writePos
    }
}
