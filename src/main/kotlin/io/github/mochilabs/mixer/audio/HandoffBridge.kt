package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBufferFactory
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameRebuilder
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers the start-up latency of a crossfade handoff clone with audio that
 * was already mixed.
 *
 * At handoff the outgoing track's frame buffer still holds up to
 * `frameBufferDuration` of finished output: the incoming track at full gain.
 * `startTrack` stops that executor, and the clone must open and seek its
 * source before it produces a frame. On a remote source that takes hundreds
 * of milliseconds or more, and the player provides nothing meanwhile.
 *
 * The bridge moves those buffered frames to the front of the clone's buffer
 * and starts the clone where they end. Lavaplayer creates the clone's buffer
 * synchronously inside `startTrack`, after it has stopped the outgoing
 * executor, so [GuildMixer] passes the outgoing buffer through a thread-local
 * that the wrapping [AudioFrameBufferFactory] picks up.
 */
internal object HandoffBridge {
    /** Supplies the prefix frames; runs inside `startTrack`, before the clone's executor exists. */
    fun interface PrefixBuilder {
        fun build(format: AudioDataFormat): List<AudioFrame>?

        /**
         * The clone's source delivered its first frame [startupMs] after its
         * buffer was created, with [prefixLeftFrames] bridge frames unplayed.
         * Runs on the clone's decoder thread.
         */
        fun onSourceStarted(startupMs: Long, prefixLeftFrames: Int) = Unit
    }

    private val pending = ThreadLocal<PrefixBuilder?>()

    /** Wraps the configured frame-buffer factory once. Safe to call repeatedly. */
    fun install(configuration: AudioConfiguration): Boolean {
        val current = configuration.frameBufferFactory ?: return false
        if (current is Factory) return true
        configuration.frameBufferFactory = Factory(current)
        return true
    }

    fun isInstalled(configuration: AudioConfiguration): Boolean = configuration.frameBufferFactory is Factory

    /** Runs [block] (which must start the clone on this thread) with [builder] armed for the next buffer. */
    fun <T> withPrefix(builder: PrefixBuilder, block: () -> T): T {
        pending.set(builder)
        try {
            return block()
        } finally {
            pending.remove()
        }
    }

    /** The frame buffer of a playing local track, or null for anything else. */
    fun bufferOf(track: AudioTrack?): AudioFrameBuffer? =
        ((track as? InternalAudioTrack)?.activeExecutor as? LocalAudioTrackExecutor)?.audioBuffer

    /**
     * Takes every frame left in [source] whose timecode is at most
     * [lastTimecode]. The source belongs to an executor that was just
     * stopped; nothing else will read it once the player switched tracks.
     */
    fun drain(source: AudioFrameBuffer, lastTimecode: Long, format: AudioDataFormat): List<AudioFrame> {
        val out = ArrayList<AudioFrame>()
        val buffer = ByteBuffer.allocate(format.maximumChunkSize())
        val frame = MutableAudioFrame().apply { setBuffer(buffer) }
        while (true) {
            if (!source.provide(frame) || frame.isTerminator) break
            if (frame.timecode > lastTimecode) break
            val data = buffer.array().copyOf(frame.dataLength)
            out += ImmutableAudioFrame(frame.timecode, data, frame.volume, frame.format ?: format)
        }
        return out
    }

    private class Factory(private val delegate: AudioFrameBufferFactory) : AudioFrameBufferFactory {
        override fun create(bufferDuration: Int, format: AudioDataFormat, stopping: AtomicBoolean): AudioFrameBuffer {
            val inner = delegate.create(bufferDuration, format, stopping)
            val builder = pending.get() ?: return inner
            pending.remove()
            val prefix = try {
                builder.build(format)
            } catch (_: Exception) {
                null
            }
            if (prefix.isNullOrEmpty()) return inner
            return BridgingFrameBuffer(inner, format, prefix, builder::onSourceStarted)
        }
    }
}

/**
 * Plays [prefix] before anything [delegate] holds. The prefix survives the
 * clear that the clone's own initial seek performs (with or without seek
 * ghosting); once the clone's source has delivered a frame, a further clear
 * or seek drops it like any other buffered audio.
 */
internal class BridgingFrameBuffer(
    private val delegate: AudioFrameBuffer,
    private val format: AudioDataFormat,
    prefix: List<AudioFrame>,
    private val onSourceStarted: (startupMs: Long, prefixLeftFrames: Int) -> Unit = { _, _ -> },
) : AudioFrameBuffer by delegate {
    private val createdAtNanos = System.nanoTime()
    private val lock = Any()
    private val prefix = ArrayDeque(prefix)
    @Volatile private var sourceStarted = false
    private var dropPrefixOnInsert = false

    val prefixFrames: Int get() = synchronized(lock) { prefix.size }

    private fun nextPrefix(): AudioFrame? = synchronized(lock) { prefix.removeFirstOrNull() }?.let { frame ->
        if (frame.volume == 0) ImmutableAudioFrame(frame.timecode, format.silenceBytes(), 0, format) else frame
    }

    override fun provide(): AudioFrame? = nextPrefix() ?: delegate.provide()

    override fun provide(timeout: Long, unit: TimeUnit): AudioFrame? = nextPrefix() ?: delegate.provide(timeout, unit)

    override fun provide(targetFrame: MutableAudioFrame): Boolean {
        val frame = nextPrefix() ?: return delegate.provide(targetFrame)
        copy(frame, targetFrame)
        return true
    }

    override fun provide(targetFrame: MutableAudioFrame, timeout: Long, unit: TimeUnit): Boolean {
        val frame = nextPrefix() ?: return delegate.provide(targetFrame, timeout, unit)
        copy(frame, targetFrame)
        return true
    }

    private fun copy(frame: AudioFrame, target: MutableAudioFrame) {
        target.timecode = frame.timecode
        target.volume = frame.volume
        target.store(frame.data, 0, frame.dataLength)
        target.isTerminator = false
        target.format = frame.format
    }

    override fun consume(frame: AudioFrame) {
        var firstLeft = -1
        synchronized(lock) {
            if (!sourceStarted) firstLeft = prefix.size
            sourceStarted = true
            if (dropPrefixOnInsert) {
                prefix.clear()
                dropPrefixOnInsert = false
            }
        }
        if (firstLeft >= 0) {
            try {
                onSourceStarted((System.nanoTime() - createdAtNanos) / 1_000_000L, firstLeft)
            } catch (_: Exception) {
                // Diagnostics only.
            }
        }
        delegate.consume(frame)
    }

    override fun clear() {
        if (sourceStarted) synchronized(lock) { prefix.clear() }
        delegate.clear()
    }

    override fun setClearOnInsert() {
        if (sourceStarted) synchronized(lock) { dropPrefixOnInsert = true }
        delegate.setClearOnInsert()
    }

    override fun rebuild(rebuilder: AudioFrameRebuilder) {
        synchronized(lock) {
            val rebuilt = prefix.map { rebuilder.rebuild(it) }
            prefix.clear()
            prefix.addAll(rebuilt)
        }
        delegate.rebuild(rebuilder)
    }
}

/**
 * Recent (main chain sample → incoming source milli-samples) pairs, one per
 * mixed chunk, so a handoff can tell which incoming source position any
 * buffered output frame ends at. Written by the elected feeder, frozen and
 * read by the handoff. Chunks recorded after [freeze] are ignored: their
 * output is never bridged.
 */
internal class HandoffSourceMap(capacity: Int = 1024) {
    data class Snapshot(val chainEnds: LongArray, val sourceMilliSamples: LongArray) {
        val lastChainEnd: Long get() = chainEnds.lastOrNull() ?: -1L

        /** Source milli-samples at [chainSample], or null when it is outside what was recorded. */
        fun sourceAt(chainSample: Long): Long? {
            if (chainEnds.isEmpty() || chainSample > chainEnds.last()) return null
            val hi = chainEnds.indexOfFirst { it >= chainSample }
            if (hi <= 0) return if (hi == 0 && chainEnds[0] == chainSample) sourceMilliSamples[0] else null
            val lo = hi - 1
            val span = chainEnds[hi] - chainEnds[lo]
            if (span <= 0L) return sourceMilliSamples[hi]
            val f = (chainSample - chainEnds[lo]).toDouble() / span
            return sourceMilliSamples[lo] + ((sourceMilliSamples[hi] - sourceMilliSamples[lo]) * f).toLong()
        }
    }

    private val lock = Any()
    private val chainEnds = LongArray(capacity)
    private val sources = LongArray(capacity)
    private var head = 0
    private var size = 0
    private var frozen = false

    fun record(chainEnd: Long, sourceMilliSamples: Long): Unit = synchronized(lock) {
        if (frozen) return
        if (size > 0 && chainEnd <= chainEnds[(head + size - 1) % chainEnds.size]) {
            // A rewound chain (seek) invalidates the history.
            size = 0
        }
        val idx = (head + size) % chainEnds.size
        chainEnds[idx] = chainEnd
        sources[idx] = sourceMilliSamples
        if (size < chainEnds.size) size++ else head = (head + 1) % chainEnds.size
    }

    fun freeze(): Snapshot = synchronized(lock) {
        frozen = true
        Snapshot(
            LongArray(size) { chainEnds[(head + it) % chainEnds.size] },
            LongArray(size) { sources[(head + it) % sources.size] },
        )
    }

    fun reset(): Unit = synchronized(lock) {
        size = 0
        head = 0
        frozen = false
    }
}
