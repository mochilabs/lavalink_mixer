package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Mix point on the main player's filter chain (registered via the
 * "mixer" [MixerFilterExtension]).
 *
 * Lavaplayer pipelines data only through build-time downstream references:
 * the decoder writes into the chain head and each filter must feed its
 * [downstream], ending at the frame buffer. [MixerFilterExtension.build]
 * receives exactly that downstream and hands it here — so after mixing,
 * [process] forwards the same (mutated) array to it. Without the forward
 * the chain is severed: no frame ever reaches the buffer and Lavaplayer
 * reports a healthy track as stuck.
 *
 * The [GuildMixer] is resolved through [mixerProvider] on every chunk rather
 * than captured at build time. The factory is only consulted once, when the
 * track starts; capturing then would pin whatever instance existed at that
 * instant, and a guild mixer that is created (or replaced) afterwards would
 * never see a single sample — a silent "crossfade that only starts the next
 * track when the old one ends". Resolving costs one ConcurrentHashMap read
 * per 20ms chunk.
 *
 * output = main * mainGain + sub * subGain, soft-limited into [-1, 1] (see
 * [softLimit]).
 * Gains are advanced once per process() call (per chunk, not per sample);
 * with ~20ms chunks that is ~50 gain steps/second, free of zipper noise
 * for ramps >= 80ms. When idle (main=1, sub=0) chunks pass through
 * unmodified.
 *
 * Never lets mixer bugs break main output: a mixer exception degrades to
 * passthrough (logged, throttled) while the chunk still goes downstream —
 * the worst case is unmixed audio, never silence.
 */
class MixerFilter(
    private val guildId: Long,
    private val mixerProvider: () -> GuildMixer?,
    private val downstream: FloatPcmAudioFilter?,
) : FloatPcmAudioFilter {

    constructor(mixer: GuildMixer, downstream: FloatPcmAudioFilter?) : this(mixer.guildId, { mixer }, downstream)

    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        val mixer = mixerProvider()
        if (mixer == null) {
            warnUnbound()
            downstream?.process(input, offset, length)
            return
        }
        // Heartbeat for GuildMixer's "is this filter actually in the chain"
        // probe: a playing track whose position advances while this never
        // ticks means the filters op never reached the running track.
        mixer.noteFilterRun()
        try {
            mixInto(mixer, input, offset, length)
        } catch (e: Exception) {
            // Leave input (possibly) untouched and throttle the log:
            // this runs ~50x/sec, so only the first occurrence per minute.
            if (now() - lastWarn >= 60_000) {
                lastWarn = now()
                log.warn("mixer filter failed on guild {}, degrading to passthrough", guildId, e)
            }
        }
        // Always forward, even after a mixer failure — stock chain semantics.
        downstream?.process(input, offset, length)
    }

    private fun mixInto(mixer: GuildMixer, input: Array<FloatArray>, offset: Int, length: Int) {
        // Realtime tail watch runs on the raw main input, before gains.
        mixer.realtimeTailAmp()?.let { thresholdAmp ->
            var peak = 0f
            for (c in input.indices) {
                val channel = input[c]
                val end = offset + length
                for (i in offset until end) {
                    val a = abs(channel[i])
                    if (a > peak) peak = a
                }
            }
            mixer.noteChunk(peak, length, thresholdAmp)
        }

        val gains = mixer.advance(length)
        val parallels = mixer.advanceParallelOverlays(length)
        if (gains.main >= 0.999f && gains.sub <= 0.001f && parallels.isEmpty()) return // idle passthrough

        if (gains.sub <= 0.001f && parallels.isEmpty()) {
            // Main-only (fade-in/mask/duck release): scale in place, no sub buffer.
            if (gains.main < 0.999f) {
                for (c in input.indices) {
                    val channel = input[c]
                    val end = offset + length
                    for (i in offset until end) {
                        channel[i] = (channel[i] * gains.main).coerceIn(-1f, 1f)
                    }
                }
            }
            return
        }

        val sub = Array(input.size) { FloatArray(length) }
        val subSamples = mixer.takeSub(length, input.size, sub)
        mixer.noteSubOutput(subSamples)
        if (subSamples < length) mixer.noteUnderrun(length - subSamples, length)

        // Sum each parallel overlay's siphon at its fade-ramp gain.
        val parallel = if (parallels.isEmpty()) null else Array(input.size) { FloatArray(length) }
        if (parallel != null) {
            val buf = Array(input.size) { FloatArray(length) }
            for (slot in parallels) {
                slot.siphon.take(length, input.size, buf)
                val g = slot.ramp.current
                for (c in input.indices) {
                    val pc = parallel[c]
                    val bc = buf[c]
                    for (i in 0 until length) pc[i] += bc[i] * g
                }
            }
        }

        for (c in input.indices) {
            val channel = input[c]
            val subChannel = sub[c]
            val end = offset + length
            for (i in offset until end) {
                var sum = channel[i] * gains.main + subChannel[i - offset] * gains.sub
                if (parallel != null) sum += parallel[c][i]
                channel[i] = softLimit(sum)
            }
        }
    }

    private fun warnUnbound() {
        val t = now()
        if (t - lastWarn < 60_000) return
        lastWarn = t
        log.warn("mixer filter for guild {} has no GuildMixer; passing audio through untouched", guildId)
    }

    // Lifecycle events are delivered to every filter in the pipeline by
    // Lavaplayer itself; forwarding them here would double-call downstream.
    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        if (providedTime >= 0) mixerProvider()?.syncChainPos(providedTime)
    }

    override fun flush() = Unit

    override fun close() = Unit

    /**
     * Soft-knee limiter for the crossfade sum. The equal-power curve
     * (cos/sin) keeps perceived loudness flat through the overlap, but two
     * correlated full-scale sources sum to ~1.414x at the midpoint — the
     * old hard clamp flattened that into a square wave (the clipping).
     *
     * The curve is identity up to [SOFT_LIMIT_THRESHOLD] so normal audio is
     * untouched; only the excursion past the threshold is compressed, smoothly
     * and monotonically toward ±1 with no discontinuity. The knee starts just
     * below full scale because a limiter that is exactly unity AT 1.0 has no
     * headroom left — it would either hard-clip or drop over-limit samples to
     * silence. Starting at 0.9 compresses only the top 10% of the range by a
     * hair (0.9 stays 0.9, full scale is pulled to ~0.95, a 1.414 sum to
     * ~0.98) — inaudible, and only ever active during an overlap.
     */
    private fun softLimit(sample: Float): Float {
        val a = abs(sample)
        if (a <= SOFT_LIMIT_THRESHOLD) return sample
        val x = (a - SOFT_LIMIT_THRESHOLD) / (1f - SOFT_LIMIT_THRESHOLD)
        val limited = SOFT_LIMIT_THRESHOLD + (1f - SOFT_LIMIT_THRESHOLD) * (x / (1f + x))
        return if (sample >= 0f) limited else -limited
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixerFilter::class.java)

        /** Knee start; audio below this passes through bit-identically. */
        private const val SOFT_LIMIT_THRESHOLD: Float = 0.9f

        @Volatile
        private var lastWarn: Long = 0L

        private fun now(): Long = System.currentTimeMillis()
    }
}
