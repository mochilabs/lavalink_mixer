package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixerFilterTest {
    private fun mixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
    }

    @Test
    fun `idle filter passes audio through`() {
        val filter = MixerFilter(mixer(), null)
        val input = arrayOf(floatArrayOf(0.1f, -0.2f, 0.3f), floatArrayOf(-0.1f, 0.2f, -0.3f))
        filter.process(input, 0, 3)
        assertContentEquals(floatArrayOf(0.1f, -0.2f, 0.3f), input[0])
        assertContentEquals(floatArrayOf(-0.1f, 0.2f, -0.3f), input[1])
    }

    @Test
    fun `mixing applies gains per channel`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0.5f)
        m.siphon.push(arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f)), 0, 2)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.2f, 0.2f), floatArrayOf(-0.4f, -0.4f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.6f, 0.6f), input[0])
        assertContentEquals(floatArrayOf(0.3f, 0.3f), input[1])
    }

    @Test
    fun `reused sub buffer preserves offsets, resize, and underflow silence`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0.5f)
        val filter = MixerFilter(m, null)

        m.siphon.push(
            arrayOf(floatArrayOf(0.4f, 0.4f, 0.4f), floatArrayOf(-0.2f, -0.2f, -0.2f)),
            0,
            3,
        )
        val first = arrayOf(
            floatArrayOf(0f, 0.2f, 0.2f, 0.2f, 0f),
            floatArrayOf(0f, -0.4f, -0.4f, -0.4f, 0f),
        )
        filter.process(first, 1, 3)
        assertEquals(0f, first[0][0])
        assertEquals(0f, first[0][4])
        assertEquals(0.3f, first[0][1], 1e-6f)
        assertEquals(-0.3f, first[1][3], 1e-6f)

        // The prior sub samples must not leak into this shorter underflowing chunk.
        val second = arrayOf(
            floatArrayOf(0f, 0f, 0.6f, 0.6f, 0f),
            floatArrayOf(0f, 0f, -0.2f, -0.2f, 0f),
        )
        filter.process(second, 2, 2)
        assertEquals(0.3f, second[0][2], 1e-6f)
        assertEquals(-0.1f, second[1][3], 1e-6f)

        // A larger chunk grows the scratch once and still mixes the full request.
        m.siphon.push(
            arrayOf(FloatArray(5) { 0.2f }, FloatArray(5) { -0.2f }),
            0,
            5,
        )
        val third = arrayOf(FloatArray(7) { 0.2f }, FloatArray(7) { -0.2f })
        filter.process(third, 1, 5)
        assertEquals(0.2f, third[0][1], 1e-6f)
        assertEquals(-0.2f, third[1][5], 1e-6f)
    }

    @Test
    fun `parallel overlay mixes at nonzero input offset`() {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(124L, manager, MixerConfig(), java.util.concurrent.Executor { it.run() })
        try {
            mixer.gainsForTest(1f, 0f)
            val slot = OverlaySlot(1L, manager.createPlayer(), SiphonQueue()).apply {
                active = true
                ramp.setNow(0.5f)
                siphon.push(arrayOf(floatArrayOf(0.4f, 0.4f), floatArrayOf(-0.4f, -0.4f)), 0, 2)
            }
            mixer.installParallelOverlayForTest(slot)
            val input = arrayOf(
                floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f, 0.1f),
                floatArrayOf(-0.1f, -0.1f, -0.2f, -0.2f, -0.1f),
            )

            val filter = MixerFilter(mixer, null)
            filter.process(input, 2, 2)

            assertEquals(0.1f, input[0][1])
            assertEquals(0.4f, input[0][2], 1e-6f)
            assertEquals(0.4f, input[0][3], 1e-6f)
            assertEquals(0.1f, input[0][4])
            assertEquals(-0.4f, input[1][2], 1e-6f)
            assertEquals(-0.4f, input[1][3], 1e-6f)
            val underflow = arrayOf(
                floatArrayOf(0f, 0.2f, 0.3f, 0f),
                floatArrayOf(0f, -0.2f, -0.3f, 0f),
            )
            filter.process(underflow, 1, 2)
            assertEquals(0.2f, underflow[0][1], 1e-6f)
            assertEquals(0.3f, underflow[0][2], 1e-6f)
            assertEquals(-0.2f, underflow[1][1], 1e-6f)
            assertEquals(-0.3f, underflow[1][2], 1e-6f)
        } finally {
            mixer.destroy()
            manager.shutdown()
        }
    }

    @Test
    fun `output stays in unit range`() {
        val m = mixer()
        m.gainsForTest(1f, 1f)
        m.siphon.push(arrayOf(floatArrayOf(0.8f), floatArrayOf(-0.8f)), 0, 1)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.8f), floatArrayOf(-0.8f))
        filter.process(input, 0, 1)
        // Sum = 1.6 — above the 0.9 knee, compressed toward full scale:
        // x=(1.6-0.9)/0.1=7.0, 0.9+0.1·(7/8)=0.9875 instead of flat-topping.
        assertEquals(0.9875f, input[0][0], 1e-3f)
        assertEquals(-0.9875f, input[1][0], 1e-3f)
        assertTrue(kotlin.math.abs(input[0][0]) <= 1f)
    }

    @Test
    fun `empty sub queue with sub gain only scales main`() {
        val m = mixer()
        m.gainsForTest(0.2f, 1f)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.5f, 0.5f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.1f, 0.1f), input[0])
    }

    @Test
    fun `main-only gain scales without sub audio`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0f)
        val filter = MixerFilter(m, null)
        val input = arrayOf(floatArrayOf(0.4f, -0.4f), floatArrayOf(0.2f, -0.2f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.2f, -0.2f), input[0])
        assertContentEquals(floatArrayOf(0.1f, -0.1f), input[1])
    }

    @Test
    fun `mixed audio is forwarded to the downstream filter`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0.5f)
        m.siphon.push(arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f)), 0, 2)
        val received = mutableListOf<Pair<Array<FloatArray>, Int>>()
        val downstream = object : FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                received.add(input to length)
            }

            override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val filter = MixerFilter(m, downstream)
        val input = arrayOf(floatArrayOf(0.2f, 0.2f), floatArrayOf(-0.4f, -0.4f))
        filter.process(input, 0, 2)
        assertEquals(1, received.size)
        assertEquals(2, received[0].second)
        // The same array is forwarded after in-place mixing.
        assertContentEquals(floatArrayOf(0.6f, 0.6f), received[0].first[0])
        assertContentEquals(floatArrayOf(0.3f, 0.3f), received[0].first[1])
    }

    @Test
    fun `idle passthrough still forwards to the downstream filter`() {
        val m = mixer()
        var calls = 0
        val downstream = object : FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                calls++
            }

            override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val filter = MixerFilter(m, downstream)
        val input = arrayOf(floatArrayOf(0.1f, -0.2f), floatArrayOf(0.3f, -0.4f))
        filter.process(input, 0, 3)
        assertTrue(calls > 0, "idle chunks must still be forwarded")
        assertContentEquals(floatArrayOf(0.1f, -0.2f), input[0])
    }
}
