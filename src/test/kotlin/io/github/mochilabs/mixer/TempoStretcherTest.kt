package io.github.mochilabs.mixer

import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import java.lang.reflect.Proxy
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import kotlin.test.assertFailsWith
class TempoStretcherTest {

    @Test
    fun `unity speed is exact sample-for-sample passthrough into SiphonQueue`() {
        val queue = SiphonQueue()
        val siphonFilter = SiphonFilter(listOf(queue), downstream = null)
        var factoryCalled = false
        val stretcher = TempoStretcher(
            downstream = siphonFilter,
            channels = 2,
            sampleRate = 48000,
            initialSpeed = 1.0,
            timescaleFactory = { ds, ch, sr ->
                factoryCalled = true
                TimescalePcmAudioFilter(ds, ch, sr)
            },
        )

        assertFalse(stretcher.isActive)
        assertEquals(1.0, stretcher.effectiveSpeed)

        // 10 frames of 960 stereo samples with distinct values
        val frameCount = 10
        val frameSize = 960
        val expectedLeft = FloatArray(frameCount * frameSize)
        val expectedRight = FloatArray(frameCount * frameSize)

        for (f in 0 until frameCount) {
            val left = FloatArray(frameSize) { i -> (f * frameSize + i).toFloat() * 0.001f }
            val right = FloatArray(frameSize) { i -> -left[i] }
            System.arraycopy(left, 0, expectedLeft, f * frameSize, frameSize)
            System.arraycopy(right, 0, expectedRight, f * frameSize, frameSize)
            stretcher.process(arrayOf(left, right), 0, frameSize)
        }

        // Factory was never even invoked; zero allocation/latency.
        assertFalse(factoryCalled)
        assertEquals(frameCount * frameSize, queue.pendingSamples)

        val out = Array(2) { FloatArray(frameCount * frameSize) }
        val taken = queue.take(frameCount * frameSize, 2, out)
        assertEquals(frameCount * frameSize, taken)
        assertContentEquals(expectedLeft, out[0])
        assertContentEquals(expectedRight, out[1])
    }

    @Test
    fun `speed not unity with real TimescalePcmAudioFilter changes sample count in expected direction`() {
        val frameCount = 100
        val frameSize = 960
        val totalInputSamples = frameCount * frameSize // 96,000 samples = 2.0s at 48kHz

        // 1.06x speed (faster -> fewer output samples than input)
        val queueFast = SiphonQueue()
        val filterFast = SiphonFilter(listOf(queueFast), downstream = null)
        val stretcherFast = TempoStretcher(
            downstream = filterFast,
            channels = 2,
            sampleRate = 48000,
            initialSpeed = 1.06,
        )

        assertTrue(stretcherFast.isActive)
        assertEquals(1.06, stretcherFast.effectiveSpeed)

        val chunkLeft = FloatArray(frameSize) { i -> sin(2.0 * Math.PI * 440.0 * i / 48000.0).toFloat() }
        val chunkRight = FloatArray(frameSize) { i -> chunkLeft[i] * 0.5f }
        val chunk = arrayOf(chunkLeft, chunkRight)

        for (f in 0 until frameCount) {
            stretcherFast.process(chunk, 0, frameSize)
        }

        val fastSamples = queueFast.pendingSamples
        assertTrue(fastSamples > 0, "Fast stretcher should emit samples")
        assertTrue(
            fastSamples < totalInputSamples,
            "Speed 1.06 should produce fewer output samples than input ($fastSamples < $totalInputSamples)",
        )

        // 0.94x speed (slower -> more output samples than speed 1.06 and more than input)
        val queueSlow = SiphonQueue()
        val filterSlow = SiphonFilter(listOf(queueSlow), downstream = null)
        val stretcherSlow = TempoStretcher(
            downstream = filterSlow,
            channels = 2,
            sampleRate = 48000,
            initialSpeed = 0.94,
        )

        assertTrue(stretcherSlow.isActive)
        assertEquals(0.94, stretcherSlow.effectiveSpeed)

        for (f in 0 until frameCount) {
            stretcherSlow.process(chunk, 0, frameSize)
        }

        val slowSamples = queueSlow.pendingSamples
        assertTrue(slowSamples > 0, "Slow stretcher should emit samples")
        assertTrue(
            slowSamples > fastSamples,
            "Speed 0.94 should produce more output samples than speed 1.06 ($slowSamples > $fastSamples)",
        )
        assertTrue(
            slowSamples > totalInputSamples,
            "Speed 0.94 should produce more output samples than input ($slowSamples > $totalInputSamples)",
        )
    }

    @Test
    fun `fail-closed fallback catches UnsatisfiedLinkError and forwards raw PCM downstream without loss`() {
        val queue = SiphonQueue()
        val siphonFilter = SiphonFilter(listOf(queue), downstream = null)
        val stretcher = TempoStretcher(
            downstream = siphonFilter,
            channels = 2,
            sampleRate = 48000,
            initialSpeed = 1.06,
            timescaleFactory = { _, _, _ ->
                throw UnsatisfiedLinkError("simulated native failure")
            },
        )

        assertTrue(stretcher.isActive)
        assertFalse(stretcher.nativeFailed)
        assertEquals(1.06, stretcher.effectiveSpeed)

        val frameCount = 10
        val frameSize = 960
        val expectedLeft = FloatArray(frameCount * frameSize)
        val expectedRight = FloatArray(frameCount * frameSize)

        for (f in 0 until frameCount) {
            val left = FloatArray(frameSize) { i -> (f * frameSize + i).toFloat() * 0.002f }
            val right = FloatArray(frameSize) { i -> -left[i] }
            System.arraycopy(left, 0, expectedLeft, f * frameSize, frameSize)
            System.arraycopy(right, 0, expectedRight, f * frameSize, frameSize)
            stretcher.process(arrayOf(left, right), 0, frameSize)
        }

        // Native failure caught
        assertTrue(stretcher.nativeFailed)
        assertFalse(stretcher.isActive)
        assertEquals(1.0, stretcher.effectiveSpeed)

        // All samples passed downstream cleanly without a single drop
        assertEquals(frameCount * frameSize, queue.pendingSamples)
        val out = Array(2) { FloatArray(frameCount * frameSize) }
        val taken = queue.take(frameCount * frameSize, 2, out)
        assertEquals(frameCount * frameSize, taken)
        assertContentEquals(expectedLeft, out[0])
        assertContentEquals(expectedRight, out[1])
    }

    @Test
    fun `reset resets effectiveSpeed to 1_0 while seekPerformed clears buffers and preserves targetSpeed`() {
        val queue = SiphonQueue()
        val siphonFilter = SiphonFilter(listOf(queue), downstream = null)
        val stretcher = TempoStretcher(
            downstream = siphonFilter,
            channels = 2,
            sampleRate = 48000,
            initialSpeed = 1.06,
        )

        assertEquals(1.06, stretcher.effectiveSpeed)
        assertTrue(stretcher.isActive)

        // seekPerformed (invoked by Lavaplayer when a track starts at incomingStartMs > 0)
        // clears converter state and downstream queue without clobbering targetSpeed.
        stretcher.seekPerformed(250L, 250L)
        assertEquals(1.06, stretcher.effectiveSpeed)
        assertTrue(stretcher.isActive)
        assertEquals(1.06, stretcher.targetSpeed)

        // reset() explicitly resets speed back to 1.0
        stretcher.reset()
        assertEquals(1.0, stretcher.effectiveSpeed)
        assertFalse(stretcher.isActive)
        assertEquals(1.0, stretcher.targetSpeed)
    }

    @Test
    fun `SiphonFactory speed control and chain integration`() {
        val queue = SiphonQueue()
        val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val dummyOutput = Proxy.newProxyInstance(
            UniversalPcmAudioFilter::class.java.classLoader,
            arrayOf(UniversalPcmAudioFilter::class.java),
        ) { _, _, _ -> null } as UniversalPcmAudioFilter

        // Default enableTimescale = false returns single SiphonFilter
        val defaultFactory = SiphonFactory(queue)
        val defaultChain = defaultFactory.buildChain(null, format, dummyOutput)
        assertEquals(1, defaultChain.size)
        assertTrue(defaultChain[0] is SiphonFilter)

        // enableTimescale = true returns [TempoStretcher, SiphonFilter]
        val factory = SiphonFactory(queue, enableTimescale = true)
        assertEquals(1.0, factory.effectiveSpeed)
        assertFalse(factory.isTimescaleActive)

        factory.setSpeed(1.05)
        assertEquals(1.05, factory.targetSpeed)

        val chain = factory.buildChain(null, format, dummyOutput)
        assertEquals(2, chain.size)
        assertTrue(chain[0] is TempoStretcher)
        assertTrue(chain[1] is SiphonFilter)

        assertEquals(1.05, factory.effectiveSpeed)
        assertTrue(factory.isTimescaleActive)

        factory.resetSpeed()
        assertEquals(1.0, factory.effectiveSpeed)
        assertFalse(factory.isTimescaleActive)
    }
    @Test
    fun `native processing failure preserves source progress of previously queued stretched PCM`() {
        val queue = SiphonQueue()
        var calls = 0
        val factory = SiphonFactory(queue, enableTimescale = true, timescaleFactory = { downstream, _, _ ->
            object : com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter {
                override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                    if (++calls > 1) throw UnsatisfiedLinkError("processing failed after initial output")
                    downstream.process(input, offset, length)
                }
                override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
                override fun flush() = Unit
                override fun close() = Unit
            }
        })
        factory.setSpeed(0.96)
        val output = Proxy.newProxyInstance(
            UniversalPcmAudioFilter::class.java.classLoader,
            arrayOf(UniversalPcmAudioFilter::class.java),
        ) { _, _, _ -> null } as UniversalPcmAudioFilter
        val chain = factory.buildChain(null, StandardAudioDataFormats.DISCORD_PCM_S16_LE, output)
        val input = chain.first() as com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
        input.process(Array(2) { FloatArray(100) { 0.25f } }, 0, 100)
        input.process(Array(2) { FloatArray(100) { 0.5f } }, 0, 100)
        assertTrue(factory.nativeFailed)
        assertEquals(1.0, factory.effectiveSpeed)

        val mixed = Array(2) { FloatArray(200) }
        val read = queue.takeWithSource(200, 2, mixed)
        assertEquals(200, read.outputSamples)
        assertEquals(196_000L, read.sourceMilliSamples)
        assertContentEquals(FloatArray(100) { 0.25f } + FloatArray(100) { 0.5f }, mixed[0])
        chain.forEach { it.close() }
    }
    @Test
    fun `downstream InterruptedException is rethrown and does not mark nativeFailed`() {
        val downstream = object : com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                throw InterruptedException("playback stopped during handoff")
            }
            override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val stretcher = TempoStretcher(
            downstream = downstream,
            channels = 2,
            sampleRate = 48000,
            initialSpeed = 1.06,
            timescaleFactory = { ds, _, _ ->
                object : com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter {
                    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                        ds.process(input, offset, length)
                    }
                    override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
                    override fun flush() = Unit
                    override fun close() = Unit
                }
            },
        )
        assertTrue(stretcher.isActive)
        assertFalse(stretcher.nativeFailed)

        assertFailsWith<InterruptedException> {
            stretcher.process(Array(2) { FloatArray(100) }, 0, 100)
        }

        assertFalse(stretcher.nativeFailed, "downstream InterruptedException must not be marked as a native timescale failure")
        assertTrue(stretcher.isActive)
    }

    @Test
    fun `clearNativeFailure and reset clear nativeFailed flag`() {
        val queue = SiphonQueue()
        val factory = SiphonFactory(queue, enableTimescale = true, timescaleFactory = { _, _, _ ->
            throw UnsatisfiedLinkError("simulated native failure")
        })
        factory.setSpeed(1.1)
        val output = Proxy.newProxyInstance(
            UniversalPcmAudioFilter::class.java.classLoader,
            arrayOf(UniversalPcmAudioFilter::class.java),
        ) { _, _, _ -> null } as UniversalPcmAudioFilter
        val chain = factory.buildChain(null, StandardAudioDataFormats.DISCORD_PCM_S16_LE, output)
        val input = chain.first() as com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
        input.process(Array(2) { FloatArray(100) }, 0, 100)

        assertTrue(factory.nativeFailed)

        factory.resetSpeed()
        assertFalse(factory.nativeFailed, "resetSpeed must clear stale nativeFailed flag")
    }
}
