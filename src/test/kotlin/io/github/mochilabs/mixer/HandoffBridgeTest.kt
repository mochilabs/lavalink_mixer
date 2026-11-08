package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.track.playback.AllocatingAudioFrameBuffer
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffBridgeTest {
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    private fun frame(tc: Long, volume: Int = 100) = ImmutableAudioFrame(tc, ByteArray(16) { tc.toByte() }, volume, format)

    private fun bridged(prefix: List<AudioFrame>): BridgingFrameBuffer {
        val config = AudioConfiguration()
        assertTrue(HandoffBridge.install(config))
        assertTrue(HandoffBridge.install(config), "install must be idempotent")
        val buffer = HandoffBridge.withPrefix({ prefix }) {
            config.frameBufferFactory.create(5_000, format, AtomicBoolean(false))
        }
        return assertIs<BridgingFrameBuffer>(buffer)
    }

    @Test
    fun `factory is plain delegation without a pending bridge`() {
        val config = AudioConfiguration()
        HandoffBridge.install(config)
        val buffer = config.frameBufferFactory.create(5_000, format, AtomicBoolean(false))
        assertFalse(buffer is BridgingFrameBuffer)
    }

    @Test
    fun `a pending bridge is used by exactly one buffer`() {
        val config = AudioConfiguration()
        HandoffBridge.install(config)
        HandoffBridge.withPrefix({ listOf(frame(1)) }) {
            assertIs<BridgingFrameBuffer>(config.frameBufferFactory.create(5_000, format, AtomicBoolean(false)))
            assertFalse(config.frameBufferFactory.create(5_000, format, AtomicBoolean(false)) is BridgingFrameBuffer)
        }
    }

    @Test
    fun `prefix plays first and survives the clone's initial seek`() {
        val buffer = bridged(listOf(frame(100), frame(120)))
        // Without seek ghosting the executor clears on setPosition and again
        // when it applies the seek; with ghosting it sets clear-on-insert.
        buffer.clear()
        buffer.setClearOnInsert()
        buffer.clear()
        buffer.consume(frame(140))
        assertEquals(100L, buffer.provide()?.timecode)
        assertEquals(120L, buffer.provide()?.timecode)
        assertEquals(140L, buffer.provide()?.timecode)
        assertNull(buffer.provide())
    }

    @Test
    fun `a seek after the source started drops the prefix`() {
        val buffer = bridged(listOf(frame(100), frame(120)))
        buffer.consume(frame(140))
        buffer.clear()
        assertEquals(0, buffer.prefixFrames)

        val ghosted = bridged(listOf(frame(100), frame(120)))
        ghosted.consume(frame(140))
        ghosted.setClearOnInsert()
        // Ghosting: the old audio keeps playing until the new position arrives.
        assertEquals(2, ghosted.prefixFrames)
        ghosted.consume(frame(9_000))
        assertEquals(0, ghosted.prefixFrames)
        assertEquals(9_000L, ghosted.provide()?.timecode)
    }

    @Test
    fun `muted prefix frames are provided as silence`() {
        val buffer = bridged(listOf(frame(100, volume = 0)))
        val out = buffer.provide()!!
        assertEquals(0, out.volume)
        assertTrue(out.data.all { it == 0.toByte() })
    }

    @Test
    fun `source start reports the remaining bridge`() {
        val config = AudioConfiguration()
        HandoffBridge.install(config)
        var reported = -1
        val builder = object : HandoffBridge.PrefixBuilder {
            override fun build(format: com.sedmelluq.discord.lavaplayer.format.AudioDataFormat) = listOf(frame(1), frame(2))
            override fun onSourceStarted(startupMs: Long, prefixLeftFrames: Int) { reported = prefixLeftFrames }
        }
        val buffer = HandoffBridge.withPrefix(builder) { config.frameBufferFactory.create(5_000, format, AtomicBoolean(false)) }
        buffer.provide()
        buffer.consume(frame(3))
        assertEquals(1, reported)
        reported = -1
        buffer.consume(frame(4))
        assertEquals(-1, reported, "only the first source frame reports")
    }

    @Test
    fun `drain keeps frames up to the freeze timecode`() {
        val source = AllocatingAudioFrameBuffer(5_000, format, AtomicBoolean(false))
        for (tc in listOf(100L, 120L, 140L, 160L)) source.consume(frame(tc))
        val drained = HandoffBridge.drain(source, 140L, format)
        assertEquals(listOf(100L, 120L, 140L), drained.map { it.timecode })
        assertEquals(16, drained.first().dataLength)
    }

    @Test
    fun `source map interpolates and ignores chunks after the freeze`() {
        val map = HandoffSourceMap(capacity = 4)
        map.record(960, 960_000)
        map.record(1_920, 2_880_000) // 2x speed chunk
        val snap = map.freeze()
        map.record(2_880, 3_840_000)
        assertEquals(1_920L, snap.lastChainEnd)
        assertEquals(1_920_000L, snap.sourceAt(1_440))
        assertEquals(960_000L, snap.sourceAt(960))
        assertNull(snap.sourceAt(1_921))
        assertNull(snap.sourceAt(100))
        assertEquals(1_920L, map.freeze().lastChainEnd)
        map.reset()
        map.record(100, 1)
        assertEquals(100L, map.freeze().lastChainEnd)
    }

    @Test
    fun `source map keeps the newest chunks and restarts on a rewind`() {
        val map = HandoffSourceMap(capacity = 2)
        map.record(10, 1)
        map.record(20, 2)
        map.record(30, 3)
        assertEquals(listOf(20L, 30L), map.freeze().chainEnds.toList())
        map.reset()
        map.record(50, 5)
        map.record(10, 1)
        assertEquals(listOf(10L), map.freeze().chainEnds.toList())
    }
}
