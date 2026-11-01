package dev.lavalink.mixer.plugin

import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Audio filter wrapping [TimescalePcmAudioFilter] with fail-closed semantics.
 *
 * - When [isActive] is false (i.e. speed is within 1e-4 of 1.0 or native library failed to load),
 *   it passes PCM straight downstream with zero allocation and zero latency.
 * - When [isActive] is true, it lazily creates the timescale filter.
 * - If native instantiation or processing fails with [UnsatisfiedLinkError] or any [Throwable],
 *   it logs a single warning, flips [nativeFailed] to true, and immediately falls back to
 *   un-stretched downstream forwarding without dropping samples.
 */
class TempoStretcher(
    private val downstream: FloatPcmAudioFilter,
    private val channels: Int,
    private val sampleRate: Int,
    initialSpeed: Double = 1.0,
    private val timescaleFactory: (FloatPcmAudioFilter, Int, Int) -> FloatPcmAudioFilter = { ds, ch, sr ->
        TimescalePcmAudioFilter(ds, ch, sr)
    },
) : FloatPcmAudioFilter {

    @Volatile
    var targetSpeed: Double = initialSpeed.coerceIn(0.5, 2.0)
        set(value) {
            val clamped = value.coerceIn(0.5, 2.0)
            field = clamped
            if (abs(clamped - 1.0) <= 1e-4) {
                runCatching { delegate?.seekPerformed(0L, 0L) }
            } else {
                runCatching {
                    (delegate as? TimescalePcmAudioFilter)?.setSpeed(clamped)
                }.onFailure { t ->
                    if (!nativeFailed) {
                        log.warn("Timescale setSpeed failed: {}", t.message, t)
                        nativeFailed = true
                    }
                    runCatching { delegate?.close() }
                    delegate = null
                }
            }
        }

    @Volatile
    var nativeFailed: Boolean = false
        internal set

    @Volatile
    private var delegate: FloatPcmAudioFilter? = null

    val isActive: Boolean
        get() = !nativeFailed && abs(targetSpeed - 1.0) > 1e-4

    val effectiveSpeed: Double
        get() = if (isActive) targetSpeed else 1.0

    fun updateSpeed(speed: Double) {
        targetSpeed = speed
    }

    fun reset() {
        targetSpeed = 1.0
        runCatching { delegate?.seekPerformed(0L, 0L) }
    }

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return

        if (!isActive) {
            downstream.process(input, offset, length)
            return
        }

        try {
            var current = delegate
            if (current == null) {
                current = timescaleFactory(downstream, channels, sampleRate)
                (current as? TimescalePcmAudioFilter)?.setSpeed(targetSpeed)
                delegate = current
            }
            current.process(input, offset, length)
        } catch (t: Throwable) {
            if (!nativeFailed) {
                log.warn("Timescale audio filter failed; falling back to unshifted audio: {}", t.message, t)
                nativeFailed = true
            }
            runCatching { delegate?.close() }
            delegate = null
            downstream.process(input, offset, length)
        }
    }

    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        runCatching { delegate?.seekPerformed(requestedTime, providedTime) }
        downstream.seekPerformed(requestedTime, providedTime)
    }

    override fun flush() {
        runCatching { delegate?.flush() }
        downstream.flush()
    }

    override fun close() {
        runCatching { delegate?.close() }
        delegate = null
        downstream.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(TempoStretcher::class.java)
    }
}
