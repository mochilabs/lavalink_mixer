package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiphonQueueTest {
    @Test
    fun `push and take roundtrip`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f)), 0, 2)
        val out = Array(2) { FloatArray(2) }
        q.take(2, 2, out)
        assertContentEquals(floatArrayOf(0.1f, 0.2f), out[0])
        assertContentEquals(floatArrayOf(0.3f, 0.4f), out[1])
        assertEquals(0, q.pendingFrames)
    }

    @Test
    fun `partial consumption spans frames`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(1f, 2f)), 0, 2)
        q.push(arrayOf(floatArrayOf(3f, 4f)), 0, 2)
        val out = Array(1) { FloatArray(3) }
        q.take(3, 1, out)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), out[0])
        val rest = Array(1) { FloatArray(1) }
        q.take(1, 1, rest)
        assertContentEquals(floatArrayOf(4f), rest[0])
    }

    @Test
    fun `underflow zero-fills`() {
        val q = SiphonQueue()
        val out = Array(2) { FloatArray(4) { 9f } }
        q.take(4, 2, out)
        assertContentEquals(FloatArray(4), out[0])
        assertContentEquals(FloatArray(4), out[1])
    }

    @Test
    fun `overflow drops oldest`() {
        val q = SiphonQueue(maxFrames = 2)
        q.push(arrayOf(floatArrayOf(1f)), 0, 1)
        q.push(arrayOf(floatArrayOf(2f)), 0, 1)
        q.push(arrayOf(floatArrayOf(3f)), 0, 1)
        assertEquals(2, q.pendingFrames)
        assertTrue(q.droppedFrames >= 1)
        val out = Array(1) { FloatArray(2) }
        q.take(2, 1, out)
        assertContentEquals(floatArrayOf(2f, 3f), out[0])
    }

    @Test
    fun `missing sub channels stay silent`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(0.5f, 0.5f)), 0, 2)
        val out = Array(2) { FloatArray(2) }
        q.take(2, 2, out)
        assertContentEquals(floatArrayOf(0.5f, 0.5f), out[0])
        assertContentEquals(floatArrayOf(0f, 0f), out[1])
    }

    @Test
    fun `clear empties queue`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(1f)), 0, 1)
        q.clear()
        assertEquals(0, q.pendingFrames)
        assertEquals(0, q.pendingSamples)
    }

    @Test
    fun `frame buffer prefill does not drop the head of the sub track`() {
        // Lavaplayer fills the whole frame buffer the instant playTrack()
        // returns, long before the mixer has consumed a single sample. With a
        // frame-based cap sized for "a little slack" (150 frames) every 20ms
        // source lost its first 2 seconds here — the crossfade then played
        // track B from the middle and reported a handoff position that did
        // not match what was actually heard.
        val q = SiphonQueue.forFrameBuffer(5000)
        val chunk = 960 // 20ms at 48kHz — what opus/webm sources emit
        repeat(5000 / 20) { q.push(Array(2) { FloatArray(chunk) }, 0, chunk) }

        assertEquals(0, q.droppedFrames, "sub track opening was dropped before the crossfade heard it")
        assertEquals(0L, q.droppedSamples)
        assertTrue(
            q.pendingSamples >= 4000 * 48,
            "expected most of the 5s prefill retained, got ${q.pendingSamples / 48}ms",
        )
    }

    @Test
    fun `sample accounting survives partial takes`() {
        val q = SiphonQueue(maxSamples = 1000)
        q.push(arrayOf(FloatArray(600)), 0, 600)
        assertEquals(600, q.pendingSamples)
        val out = Array(1) { FloatArray(250) }
        assertEquals(250, q.take(250, 1, out))
        assertEquals(350, q.pendingSamples)
        // Over the cap: the oldest (partially consumed) frame goes first.
        q.push(arrayOf(FloatArray(900)), 0, 900)
        assertEquals(1, q.pendingFrames)
        assertEquals(900, q.pendingSamples)
        assertEquals(1L, q.droppedFrames)
        assertEquals(350L, q.droppedSamples)
    }

    @Test
    fun `unbounded frames still drop at the sample cap`() {
        val q = SiphonQueue(maxSamples = 480)
        repeat(10) { q.push(arrayOf(FloatArray(240)), 0, 240) }
        assertEquals(2, q.pendingFrames)
        assertEquals(480, q.pendingSamples)
        assertEquals(8L, q.droppedFrames)
    }
    @Test
    fun `source progress follows each queued frame across speed changes and underflow`() {
        val q = SiphonQueue()
        q.push(arrayOf(FloatArray(100) { 0.25f }), 0, 100, sourceSpeed = 0.96)
        q.push(arrayOf(FloatArray(100) { 0.5f }), 0, 100, sourceSpeed = 1.0)
        val out = Array(1) { FloatArray(150) }

        val first = q.takeWithSource(150, 1, out)
        assertEquals(150, first.outputSamples)
        assertEquals(146_000L, first.sourceMilliSamples)
        assertContentEquals(FloatArray(100) { 0.25f } + FloatArray(50) { 0.5f }, out[0])

        val second = q.takeWithSource(150, 1, out)
        assertEquals(50, second.outputSamples)
        assertEquals(50_000L, second.sourceMilliSamples)
        assertContentEquals(FloatArray(50) { 0.5f } + FloatArray(100), out[0])
    }

    @Test
    fun `fragmented reads preserve fractional source progress`() {
        val q = SiphonQueue()
        q.push(arrayOf(FloatArray(1000)), 0, 1000, sourceSpeed = 1.0004)
        val out = arrayOf(FloatArray(1))
        var source = 0L
        repeat(1000) { source += q.takeWithSource(1, 1, out).sourceMilliSamples }
        assertEquals(1_000_400L, source)
    }

    @Test
    fun `missing channels overwrite reused output and dropped frames do not count as consumed`() {
        val q = SiphonQueue(maxFrames = 1)
        q.push(arrayOf(FloatArray(10)), 0, 10, sourceSpeed = 0.94)
        q.push(arrayOf(FloatArray(10) { 0.5f }), 0, 10, sourceSpeed = 1.06)
        val out = Array(2) { FloatArray(10) { 9f } }
        val read = q.takeWithSource(10, 2, out)
        assertEquals(10_600L, read.sourceMilliSamples)
        assertContentEquals(FloatArray(10) { 0.5f }, out[0])
        assertContentEquals(FloatArray(10), out[1])
    }

    @Test
    fun `discard spans partial frames at their recorded speeds and stops at underflow`() {
        val q = SiphonQueue()
        q.push(arrayOf(FloatArray(100) { 0.25f }), 0, 100, sourceSpeed = 0.96)
        q.push(arrayOf(FloatArray(100) { 0.5f }), 0, 100, sourceSpeed = 1.06)
        val initial = arrayOf(FloatArray(20))
        assertEquals(19_200L, q.takeWithSource(20, 1, initial).sourceMilliSamples)

        val discarded = q.discard(100)
        assertEquals(100, discarded.outputSamples)
        assertEquals(98_000L, discarded.sourceMilliSamples)
        assertEquals(80, q.pendingSamples)
        val audible = arrayOf(FloatArray(10))
        assertEquals(10_600L, q.takeWithSource(10, 1, audible).sourceMilliSamples)
        assertContentEquals(FloatArray(10) { 0.5f }, audible[0])

        val remainder = q.discard(100)
        assertEquals(70, remainder.outputSamples)
        assertEquals(74_200L, remainder.sourceMilliSamples)
        assertEquals(0, q.pendingSamples)
        assertEquals(SiphonQueue.Read(0, 0L), q.discard(100))
        assertEquals(0L, q.droppedSamples, "explicit timeline advancement is not queue overflow")
    }

    @Test
    fun `positioned read counts overflow once when catching up to a later output coordinate`() {
        val q = SiphonQueue(maxSamples = 10)
        q.push(arrayOf(FloatArray(10) { 0.25f }), 0, 10, sourceSpeed = 0.96)
        q.take(2, 1, arrayOf(FloatArray(2)))
        q.push(arrayOf(FloatArray(8) { it.toFloat() }), 0, 8, sourceSpeed = 1.06)
        assertEquals(8L, q.droppedSamples)

        val out = arrayOf(FloatArray(4))
        val read = q.takeAt(12L, 4, 1, out)
        assertEquals(4, read.outputSamples)
        assertEquals(16L, read.outputPosition)
        assertEquals(15_960L, read.sourcePositionMilliSamples)
        assertContentEquals(floatArrayOf(2f, 3f, 4f, 5f), out[0])
    }

    @Test
    fun `positioned read retains catchup debt across starvation and resets on clear`() {
        val q = SiphonQueue()
        val out = arrayOf(FloatArray(10) { 9f })
        q.push(arrayOf(FloatArray(20) { 0.25f }), 0, 20, sourceSpeed = 0.96)
        assertEquals(SiphonQueue.PositionedRead(0, 19_200L, 20L), q.takeAt(50L, 10, 1, out))
        assertContentEquals(FloatArray(10), out[0])
        q.push(arrayOf(FloatArray(20) { 0.5f }), 0, 20)
        assertEquals(SiphonQueue.PositionedRead(0, 39_200L, 40L), q.takeAt(50L, 10, 1, out))
        q.push(arrayOf(FloatArray(20) { it.toFloat() }), 0, 20)
        assertEquals(SiphonQueue.PositionedRead(10, 59_200L, 60L), q.takeAt(50L, 10, 1, out))
        assertContentEquals(FloatArray(10) { (it + 10).toFloat() }, out[0])

        q.clear()
        q.push(arrayOf(FloatArray(10) { 0.75f }), 0, 10)
        assertEquals(SiphonQueue.PositionedRead(10, 10_000L, 10L), q.takeAt(0L, 10, 1, out))
        assertContentEquals(FloatArray(10) { 0.75f }, out[0])
    }

    @Test
    fun `positioned read skips late mixed-speed audio using its original source progress`() {
        val q = SiphonQueue()
        q.push(arrayOf(FloatArray(100) { 0.25f }), 0, 100, sourceSpeed = 0.96)
        q.push(arrayOf(FloatArray(100) { 0.5f }), 0, 100, sourceSpeed = 1.06)
        val out = arrayOf(FloatArray(20))
        val read = q.takeAt(150L, 20, 1, out)
        assertEquals(SiphonQueue.PositionedRead(20, 170_200L, 170L), read)
        assertContentEquals(FloatArray(20) { 0.5f }, out[0])
    }

    @Test
    fun `overflow leaves silence until surviving PCM reaches its original output position`() {
        val q = SiphonQueue(maxSamples = 10)
        q.push(arrayOf(FloatArray(10) { 0.25f }), 0, 10, sourceSpeed = 0.96)
        q.push(arrayOf(FloatArray(8) { (it + 1).toFloat() }), 0, 8, sourceSpeed = 1.06)
        val early = arrayOf(FloatArray(6) { 9f })
        assertEquals(SiphonQueue.PositionedRead(0, 9_600L, 10L), q.takeAt(0L, 6, 1, early))
        assertContentEquals(FloatArray(6), early[0])
        assertEquals(8, q.pendingSamples)

        val overlap = arrayOf(FloatArray(8) { 9f })
        assertEquals(SiphonQueue.PositionedRead(4, 13_840L, 14L), q.takeAt(6L, 8, 1, overlap))
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f), overlap[0])
        assertEquals(4, q.pendingSamples)
    }

}

