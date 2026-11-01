package dev.lavalink.mixer.plugin

import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Pitch-preserving native tempo control. A fresh unity-speed chain bypasses the
 * converter; recovery keeps an existing converter alive so buffered audio is
 * not discarded when its requested speed reaches unity.
 *
 * Native output can arrive several submissions later. FIFO rate spans integrate
 * source progress using the rate at submission, rather than relabeling buffered
 * audio with the latest request. SoundTouch does not expose an exact source
 * position for each output sample; this is the continuous tempo integral.
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
        set(value) { field = value.coerceIn(0.5, 2.0) }

    @Volatile private var envelope: TempoEnvelope? = null
    private var submittedOutput = 0.0
    private var emittedOutput = 0L
    private var appliedRate: Double? = null
    private data class RateSpan(var outputSamples: Double, val rate: Double)
    private val pendingRates = ArrayDeque<RateSpan>()

    /** Rate belonging to the PCM currently being delivered, not the latest decoder setting. */
    var outputSourceSpeed: Double = 1.0
        private set

    fun configureTempo(initialRate: Double, holdOutputSamples: Long, recoveryOutputSamples: Long) {
        envelope = TempoEnvelope(initialRate, holdOutputSamples, recoveryOutputSamples)
        targetSpeed = initialRate
        submittedOutput = 0.0
        emittedOutput = 0L
        pendingRates.clear()
    }

    private val nativeOutput = object : FloatPcmAudioFilter {
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                while (pendingRates.firstOrNull()?.outputSamples?.let { it <= 1e-9 } == true) pendingRates.removeFirst()
                val span = pendingRates.firstOrNull()
                val count = minOf(remaining, span?.outputSamples?.let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) } ?: remaining)
                // A fractional boundary sample represents contributions from adjacent rates.
                var needed = count.toDouble()
                var source = 0.0
                while (needed > 1e-9 && pendingRates.isNotEmpty()) {
                    val head = pendingRates.first()
                    val consumed = minOf(needed, head.outputSamples)
                    source += consumed * head.rate
                    head.outputSamples -= consumed
                    needed -= consumed
                    if (head.outputSamples <= 1e-9) pendingRates.removeFirst()
                }
                source += needed * targetSpeed
                outputSourceSpeed = source / count
                downstream.process(input, cursor, count)
                emittedOutput += count
                cursor += count
                remaining -= count
            }
        }
        override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
        override fun flush() = Unit
        override fun close() = Unit
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
        envelope = null
        targetSpeed = speed
    }

    fun reset() {
        envelope = null
        targetSpeed = 1.0
        submittedOutput = 0.0
        emittedOutput = 0L
        pendingRates.clear()
        runCatching { delegate?.seekPerformed(0L, 0L) }
    }

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            // Small fixed slices keep rate changes independent of decoder chunk size.
            val count = minOf(end - cursor, (sampleRate / 200).coerceAtLeast(1))
            envelope?.let { targetSpeed = it.rateAt(submittedOutput) }
            if (nativeFailed || (!isActive && delegate == null)) {
                outputSourceSpeed = 1.0
                downstream.process(input, cursor, count)
                submittedOutput += count
                emittedOutput += count
                cursor += count
                continue
            }
            val beforeOutput = emittedOutput
            try {
                var current = delegate
                if (current == null) {
                    current = timescaleFactory(nativeOutput, channels, sampleRate)
                    delegate = current
                }
                if (appliedRate != targetSpeed) {
                    (current as? TimescalePcmAudioFilter)?.setSpeed(targetSpeed)
                    appliedRate = targetSpeed
                }
                val projectedOutput = count / targetSpeed
                pendingRates.addLast(RateSpan(projectedOutput, targetSpeed))
                submittedOutput += projectedOutput
                current.process(input, cursor, count)
            } catch (t: Throwable) {
                if (!nativeFailed) log.warn("Timescale audio filter failed; falling back to unshifted audio: {}", t.message, t)
                nativeFailed = true
                runCatching { delegate?.close() }
                delegate = null
                appliedRate = null
                pendingRates.clear()
                // Do not replay a block that the delegate already emitted before failing.
                if (emittedOutput == beforeOutput) {
                    outputSourceSpeed = 1.0
                    downstream.process(input, cursor, count)
                    emittedOutput += count
                }
            }
            cursor += count
        }
    }

    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        submittedOutput = 0.0
        emittedOutput = 0L
        pendingRates.clear()
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
        appliedRate = null
        pendingRates.clear()
        downstream.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(TempoStretcher::class.java)
    }
}
