package io.github.mochilabs.mixer

import com.github.natanbc.lavadsp.natives.TimescaleConverter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter

/**
 * Pitch-preserving tempo change through one multichannel SoundTouch instance.
 *
 * lavadsp's `TimescalePcmAudioFilter` runs a separate mono SoundTouch per channel,
 * so each channel picks its own WSOLA splice offset. Left and right then drift
 * apart by up to the seek window, which audibly smears the stereo image while
 * stretching. A single instance created with the real channel count searches the
 * overlap on all channels together and splices them at the same offset.
 *
 * SoundTouch takes interleaved input and counts lengths in frames.
 */
class LinkedTimescaleFilter(
    private val downstream: FloatPcmAudioFilter,
    private val channels: Int,
    sampleRate: Int,
    private val converter: TimescaleConverter = TimescaleConverter(channels, sampleRate),
) : FloatPcmAudioFilter {
    init {
        require(channels >= 1)
    }

    private var interleavedIn = FloatArray(0)
    private val interleavedOut = FloatArray(BUFFER_FRAMES * channels)
    private val planar = Array(channels) { FloatArray(BUFFER_FRAMES) }

    fun setSpeed(speed: Double) {
        converter.setSpeed(speed)
    }

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        val needed = length * channels
        if (interleavedIn.size < needed) interleavedIn = FloatArray(needed)
        val src = interleavedIn
        for (c in 0 until channels) {
            // A mono source feeding a stereo chain duplicates its only channel.
            val channel = input[minOf(c, input.size - 1)]
            var j = c
            for (i in offset until offset + length) {
                src[j] = channel[i]
                j += channels
            }
        }
        emit(converter.process(src, 0, length, interleavedOut, 0, BUFFER_FRAMES))
        drain()
    }

    private fun drain() {
        while (true) {
            val frames = converter.read(interleavedOut, 0, BUFFER_FRAMES)
            if (frames <= 0) return
            emit(frames)
        }
    }

    private fun emit(frames: Int) {
        if (frames <= 0) return
        val out = interleavedOut
        for (c in 0 until channels) {
            val channel = planar[c]
            var j = c
            for (i in 0 until frames) {
                channel[i] = out[j]
                j += channels
            }
        }
        downstream.process(planar, 0, frames)
    }

    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        converter.reset()
    }

    // Matches lavadsp: SoundTouch's flush pads with silence, and draining that padding
    // would count frames that never existed in the source toward handoff progress.
    override fun flush() {
        converter.flush()
    }

    override fun close() {
        converter.close()
    }

    companion object {
        private const val BUFFER_FRAMES = 4096
    }
}
