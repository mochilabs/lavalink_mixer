package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempoEnvelopeTest {
    private fun output(): UniversalPcmAudioFilter = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(UniversalPcmAudioFilter::class.java),
    ) { _, _, _ -> null } as UniversalPcmAudioFilter

    @Test
    fun `profile holds overlap and smoothly recovers in output coordinates`() {
        for (rate in listOf(0.94, 1.06)) {
            val envelope = TempoEnvelope(rate, 48000, 24000)
            assertEquals(rate, envelope.rateAt(0.0))
            assertEquals(rate, envelope.rateAt(48000.0))
            assertEquals((rate + 1) / 2, envelope.rateAt(60000.0), 1e-12)
            assertEquals(1.0, envelope.rateAt(72000.0))
            assertEquals(1.0, envelope.rateAt(100000.0))
            assertTrue(abs(envelope.rateAt(48001.0) - rate) < 1e-8)
            assertTrue(abs(envelope.rateAt(71999.0) - 1) < 1e-8)
        }
    }

    @Test
    fun `delayed native output retains submitted rate after requested speed changes`() {
        val queue = SiphonQueue()
        var calls = 0
        val factory = SiphonFactory(queue, true) { downstream, _, _ ->
            object : FloatPcmAudioFilter {
                override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                    if (++calls == 2) {
                        downstream.process(Array(2) { FloatArray(200) { 0.25f } }, 0, 200)
                        downstream.process(input, offset, length)
                    }
                }
                override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
                override fun flush() = Unit
                override fun close() = Unit
            }
        }
        factory.setSpeed(0.5)
        val chain = factory.buildChain(null, StandardAudioDataFormats.DISCORD_PCM_S16_LE, output())
        val filter = chain.first() as FloatPcmAudioFilter
        filter.process(Array(2) { FloatArray(100) }, 0, 100)
        assertEquals(0, queue.pendingSamples)
        factory.setSpeed(1.0)
        filter.process(Array(2) { FloatArray(100) { 0.5f } }, 0, 100)
        assertEquals(100000L, queue.discard(200).sourceMilliSamples)
        assertEquals(100000L, queue.discard(100).sourceMilliSamples)
        chain.forEach { it.close() }
    }

    @Test
    fun `real native recovery preserves pitch and returns to unity before continued playback`() {
        for (rate in listOf(0.94, 1.06)) {
            val queue = SiphonQueue()
            val factory = SiphonFactory(queue, true)
            factory.configureTempo(rate, 48000, 48000)
            val chain = factory.buildChain(null, StandardAudioDataFormats.DISCORD_PCM_S16_LE, output())
            try {
                val filter = chain.first() as FloatPcmAudioFilter
                var position = 0
                while (position < 192000) {
                    val count = minOf(960, 192000 - position)
                    val samples = FloatArray(count) { sin(2 * Math.PI * 440 * (position + it) / 48000).toFloat() * 0.5f }
                    filter.process(arrayOf(samples, samples), 0, count)
                    position += count
                }
                assertFalse(factory.nativeFailed)
                assertEquals(1.0, factory.effectiveSpeed)
                val pcm = Array(2) { FloatArray(queue.pendingSamples) }
                val first = queue.takeWithSource(96000, 2, pcm)
                // Integral of constant hold plus symmetric smooth recovery.
                val expected = (48000 * rate + 48000 * (rate + 1) / 2) * 1000
                assertTrue(abs(first.sourceMilliSamples - expected) < 30000, "source mapping: ${first.sourceMilliSamples} vs $expected")
                val remainder = Array(2) { FloatArray(queue.pendingSamples) }
                val recovered = queue.takeWithSource(remainder[0].size, 2, remainder)
                assertTrue(abs(recovered.sourceMilliSamples - recovered.outputSamples * 1000L) < 100, "unity tail source mapping")
                // Count rising zero crossings over a full second of stretched/recovering tone.
                val start = 24000
                val end = 72000
                val crossings = (start + 1 until end).count { pcm[0][it - 1] <= 0 && pcm[0][it] > 0 }
                assertTrue(crossings in 437..443, "pitch changed: $crossings Hz at rate $rate")
                val maxJump = (start + 1 until end).maxOf { abs(pcm[0][it] - pcm[0][it - 1]) }
                assertTrue(maxJump < 0.15f, "discontinuous recovery: $maxJump")
            } finally {
                chain.forEach { it.close() }
            }
        }
    }
}
