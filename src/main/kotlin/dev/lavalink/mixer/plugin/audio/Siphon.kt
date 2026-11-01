package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlin.math.roundToLong

/**
 * Bounded FIFO of decoded float PCM chunks siphoned off the secondary
 * (overlay/crossfade) player. The mixer filter on the main player drains it.
 * Overflow drops the oldest audio; underflow reads as silence.
 *
 * The bound is in *samples*, not frames: a decoder chunk is not a time unit.
 * Opus/webm sources emit 20ms chunks (960 samples), WAV ~85ms (4096), so a
 * frame cap silently changes meaning per source. More importantly the moment
 * `playTrack()` returns Lavaplayer fills the whole frame buffer
 * (`frameBufferDuration`, 5000ms by default) as fast as it can decode — none
 * of which the mixer has consumed yet. A frame-based cap therefore throws
 * away the opening seconds of every overlaid track before the crossfade
 * hears them. Size the default from the frame buffer instead.
 */
class SiphonQueue(
    private val maxFrames: Int = Int.MAX_VALUE,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    data class Read(val outputSamples: Int, val sourceMilliSamples: Long)
    data class PositionedRead(
        val outputSamples: Int,
        val sourcePositionMilliSamples: Long,
        val outputPosition: Long,
    )

    private data class Frame(
        val channels: Array<FloatArray>,
        val sourceSpeed: Double,
        var offset: Int = 0,
    )

    private val lock = Any()
    private val deque = ArrayDeque<Frame>()
    private var queuedSamples: Int = 0
    private var outputCursor: Long = 0L
    private var sourceCursorMilliSamples: Long = 0L
    var droppedFrames: Long = 0L
        private set
    var droppedSamples: Long = 0L
        private set

    fun push(input: Array<FloatArray>, offset: Int, length: Int, sourceSpeed: Double = 1.0) {
        if (length <= 0) return
        val copy = Array(input.size) { c -> input[c].copyOfRange(offset, offset + length) }
        synchronized(lock) {
            deque.addLast(Frame(copy, sourceSpeed))
            queuedSamples += length
            while ((deque.size > maxFrames || queuedSamples > maxSamples) && deque.isNotEmpty()) {
                val head = deque.removeFirst()
                val remaining = head.channels[0].size - head.offset
                queuedSamples -= remaining
                outputCursor += remaining
                sourceCursorMilliSamples += sourceProgress(head, remaining)
                droppedFrames++
                droppedSamples += remaining.toLong()
            }
        }
    }

    /** Furthest captured output position, including samples already read or discarded. */
    fun capturedOutputEnd(): Long = synchronized(lock) { outputCursor + queuedSamples }

    /** Fills [out] (each channel exactly [samples]) from the queue, zero-filling shortfall.
     *  @return samples actually taken from queued audio (excluding zero-fill). */
    fun take(samples: Int, channels: Int, out: Array<FloatArray>): Int =
        takeWithSource(samples, channels, out).outputSamples

    /** Source progress belongs to queued PCM, even if the producer's speed has since changed. */
    fun takeWithSource(samples: Int, channels: Int, out: Array<FloatArray>): Read =
        read(samples, channels, out)

    /** Advances queued output without allocating a PCM buffer. */
    fun discard(samples: Int): Read = read(samples, 0, null)

    /** Reads at an output coordinate since [clear], including any audio lost to overflow. */
    fun takeAt(outputPosition: Long, samples: Int, channels: Int, out: Array<FloatArray>): PositionedRead =
        synchronized(lock) {
            val behind = (outputPosition - outputCursor).coerceAtLeast(0L)
            if (behind > 0L) read(minOf(behind, queuedSamples.toLong()).toInt(), 0, null)
            val taken = if (outputCursor < outputPosition) {
                for (c in 0 until minOf(channels, out.size)) out[c].fill(0f, 0, samples)
                0
            } else {
                val prefix = minOf(outputCursor - outputPosition, samples.toLong()).toInt()
                val count = read(samples - prefix, channels, out).outputSamples
                if (prefix > 0) {
                    for (c in 0 until minOf(channels, out.size)) {
                        out[c].copyInto(out[c], prefix, 0, samples - prefix)
                        out[c].fill(0f, 0, prefix)
                    }
                }
                count
            }
            PositionedRead(taken, sourceCursorMilliSamples, outputCursor)
        }

    private fun sourceProgress(frame: Frame, samples: Int): Long =
        ((frame.offset + samples) * frame.sourceSpeed * 1000).roundToLong() -
            (frame.offset * frame.sourceSpeed * 1000).roundToLong()

    private fun read(samples: Int, channels: Int, out: Array<FloatArray>?): Read {
        var remaining = samples
        var outOffset = 0
        var sourceMilliSamples = 0L
        synchronized(lock) {
            while (remaining > 0 && deque.isNotEmpty()) {
                val head = deque.first()
                val channelCount = minOf(channels, head.channels.size)
                val avail = head.channels[0].size - head.offset
                val n = minOf(remaining, avail)
                if (out != null) {
                    for (c in 0 until channelCount) {
                        head.channels[c].copyInto(out[c], outOffset, head.offset, head.offset + n)
                    }
                    for (c in channelCount until minOf(channels, out.size)) {
                        out[c].fill(0f, outOffset, outOffset + n)
                    }
                }
                // Round frame boundaries so partial reads do not accumulate rounding error.
                sourceMilliSamples += sourceProgress(head, n)
                head.offset += n
                queuedSamples -= n
                if (head.offset >= head.channels[0].size) deque.removeFirst()
                remaining -= n
                outOffset += n
            }
            if (remaining > 0 && out != null) {
                for (c in 0 until minOf(channels, out.size)) {
                    out[c].fill(0f, outOffset, outOffset + remaining)
                }
            }
            outputCursor += outOffset
            sourceCursorMilliSamples += sourceMilliSamples
            return Read(outOffset, sourceMilliSamples)
        }
    }

    fun clear() {
        synchronized(lock) {
            deque.clear()
            queuedSamples = 0
            outputCursor = 0L
            sourceCursorMilliSamples = 0L
        }
    }

    val pendingFrames: Int get() = synchronized(lock) { deque.size }

    /** Decoded-but-not-yet-mixed audio, in samples per channel. */
    val pendingSamples: Int get() = synchronized(lock) { queuedSamples }

    companion object {
        /** 8s at 48kHz: comfortably above a default 5s frame-buffer prefill. */
        const val DEFAULT_MAX_SAMPLES: Int = 8000 * 48

        /** Holds a whole frame-buffer prefill plus slack for the live backlog. */
        fun forFrameBuffer(frameBufferMs: Int): SiphonQueue =
            SiphonQueue(maxSamples = ((frameBufferMs + 3000).coerceAtLeast(DEFAULT_MAX_SAMPLES / 48)) * 48)
    }
}

/**
 * Capture filter for the secondary player: copies PCM into every attached
 * tap queue and forwards it downstream to the frame buffer. Forwarding is
 * required by Lavaplayer's chain protocol (data only flows through
 * build-time downstream references); without it the sub decoder free-runs,
 * the bounded queue only ever holds the tail of the track, and the sub
 * player's own provide() stays empty (its drain does nothing).
 * The primary tap feeds the live mixer; analysis taps are attached
 * temporarily so scanning never steals live audio.
 */
class SiphonFilter(
    private val taps: List<SiphonQueue>,
    private val downstream: UniversalPcmAudioFilter?,
    private val sourceSpeed: () -> Double = { 1.0 },
) : FloatPcmAudioFilter {
    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        val speed = sourceSpeed()
        for (queue in taps) queue.push(input, offset, length, speed)
        downstream?.process(input, offset, length)
    }

    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        for (queue in taps) queue.clear()
    }

    override fun flush() {
        for (queue in taps) queue.clear()
    }

    override fun close() = Unit
}

class SiphonFactory(
    primary: SiphonQueue,
    private val enableTimescale: Boolean = false,
    private val timescaleFactory: ((FloatPcmAudioFilter, Int, Int) -> FloatPcmAudioFilter)? = null,
) : PcmFilterFactory {
    private val taps = java.util.concurrent.CopyOnWriteArrayList<SiphonQueue>(listOf(primary))

    @Volatile
    private var currentStretcher: TempoStretcher? = null

    @Volatile
    var targetSpeed: Double = 1.0

    @Volatile private var tempoEnvelope: TempoEnvelope? = null

    fun configureTempo(initialRate: Double, holdOutputSamples: Long, recoveryOutputSamples: Long) {
        val profile = TempoEnvelope(initialRate, holdOutputSamples, recoveryOutputSamples)
        tempoEnvelope = profile
        targetSpeed = initialRate
        currentStretcher?.configureTempo(initialRate, holdOutputSamples, recoveryOutputSamples)
    }

    fun addTap(queue: SiphonQueue) {
        taps.addIfAbsent(queue)
    }

    fun removeTap(queue: SiphonQueue) {
        taps.remove(queue)
    }

    fun setSpeed(speed: Double) {
        tempoEnvelope = null
        targetSpeed = speed
        currentStretcher?.updateSpeed(speed)
    }

    fun resetSpeed() {
        tempoEnvelope = null
        targetSpeed = 1.0
        currentStretcher?.reset()
    }

    val effectiveSpeed: Double
        get() = currentStretcher?.effectiveSpeed ?: 1.0

    val isTimescaleActive: Boolean
        get() = currentStretcher?.isActive == true


    val nativeFailed: Boolean
        get() = currentStretcher?.nativeFailed == true
    override fun buildChain(
        track: AudioTrack?,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
    ): MutableList<AudioFilter> {
        if (!enableTimescale) {
            return mutableListOf(SiphonFilter(taps.toList(), output))
        }
        lateinit var stretcher: TempoStretcher
        val siphonFilter = SiphonFilter(taps.toList(), output) { stretcher.outputSourceSpeed }
        stretcher = if (timescaleFactory != null) {
            TempoStretcher(siphonFilter, format.channelCount, format.sampleRate, targetSpeed, timescaleFactory)
        } else {
            TempoStretcher(siphonFilter, format.channelCount, format.sampleRate, targetSpeed)
        }
        tempoEnvelope?.let { stretcher.configureTempo(it.initialRate, it.holdOutputSamples, it.recoveryOutputSamples) }
        currentStretcher = stretcher
        return mutableListOf(stretcher, siphonFilter)
    }
}
