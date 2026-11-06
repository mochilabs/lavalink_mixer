package io.github.mochilabs.mixer

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

    /**
     * Identity for GuildMixer's feed arbitration: two chains can resolve to
     * one mixer when two players exist for the guild (stale session vs fresh
     * restore), and only one of them may advance mixer state.
     */
    private val feederId: Long = FILTER_SEQ.incrementAndGet()
    private var subScratch = emptyArray<FloatArray>()
    private var parallelScratch = emptyArray<FloatArray>()
    private var overlayScratch = emptyArray<FloatArray>()
    private val crossoverMain = CrossoverLR4()
    private val crossoverSub = CrossoverLR4()
    private var scratchMainLow = emptyArray<FloatArray>()
    private var scratchMainHigh = emptyArray<FloatArray>()
    private var scratchSubLow = emptyArray<FloatArray>()
    private var scratchSubHigh = emptyArray<FloatArray>()
    private val echoBuffer = CircularDelayBuffer()
    private var echoScratchL = FloatArray(1024)
    private var echoScratchR = FloatArray(1024)

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
            if (now() - lastWarn >= 60_000) {
                lastWarn = now()
                log.warn("mixer filter failed on guild {}, degrading to passthrough", guildId, e)
            }
        }
        // Always forward, even after a mixer failure — stock chain semantics.
        downstream?.process(input, offset, length)
    }

    private fun mixInto(mixer: GuildMixer, input: Array<FloatArray>, offset: Int, length: Int) {
        // Runs the feed election first; [isFeeder] below reads the settled state.
        val gains = mixer.advance(length, feederId)
        // A rival chain (a second player exists for this guild) must neither
        // drain the shared sub siphons nor count into tail watch — it feeds
        // dead audio anyway, so leave its chunk untouched.
        if (!mixer.isFeeder(feederId)) return

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

        val parallels = mixer.advanceParallelOverlays(length, feederId)
        val recipe = mixer.activeRecipe
        val isRecipeActive = (recipe != null)
        val echoTrigger = if (isRecipeActive) recipe?.echoTrigger else null
        val currentSample = mixer.currentXfadeSample
        val hasEcho = (isRecipeActive && echoTrigger != null && currentSample >= echoTrigger.atSample)

        if (!hasEcho) {
            val inR = if (input.size > 1) input[1] else input[0]
            echoBuffer.write(input[0], inR, offset, length)
        }

        if (!isRecipeActive && gains.main >= 0.999f && gains.sub <= 0.001f && parallels.isEmpty()) return // idle passthrough
        if (!isRecipeActive && gains.sub <= 0.001f && parallels.isEmpty()) {
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

        subScratch = ensureScratch(subScratch, input.size, length)
        val sub = subScratch
        val subSamples = mixer.takeSub(length, input.size, sub)
        mixer.noteSubOutput(subSamples)
        if (subSamples < length) mixer.noteUnderrun(length - subSamples, length)

        if (input.size >= 2 && mixer.isOverlayActive()) {
            val hasRight = sub.size > 1 && sub[1].any { it != 0f }
            val srcR = if (hasRight) sub[1] else sub[0]
            when (mixer.currentOverlayChannel()) {
                AnnounceChannel.LEFT -> {
                    sub[1].fill(0f, 0, length)
                }
                AnnounceChannel.RIGHT -> {
                    srcR.copyInto(sub[1], 0, 0, length)
                    sub[0].fill(0f, 0, length)
                }
                AnnounceChannel.BOTH -> {
                    if (!hasRight) {
                        sub[0].copyInto(sub[1], 0, 0, length)
                    }
                }
            }
        }

        // Sum each parallel overlay's siphon at its fade-ramp gain.
        val parallel = if (parallels.isEmpty()) null else {
            parallelScratch = ensureScratch(parallelScratch, input.size, length)
            parallelScratch.also { scratch -> scratch.forEach { it.fill(0f, 0, length) } }
        }
        if (parallel != null) {
            overlayScratch = ensureScratch(overlayScratch, input.size, length)
            val buf = overlayScratch
            for (slot in parallels) {
                slot.siphon.take(length, input.size, buf)
                val g = slot.ramp.current
                if (input.size >= 2) {
                    val srcL = buf[0]
                    val hasRight = buf.size > 1 && buf[1].any { it != 0f }
                    val srcR = if (hasRight) buf[1] else buf[0]
                    when (slot.channel) {
                        AnnounceChannel.LEFT -> {
                            val pc = parallel[0]
                            for (i in 0 until length) pc[i] += srcL[i] * g
                        }
                        AnnounceChannel.RIGHT -> {
                            val pc = parallel[1]
                            for (i in 0 until length) pc[i] += srcR[i] * g
                        }
                        AnnounceChannel.BOTH -> {
                            val pc0 = parallel[0]
                            val pc1 = parallel[1]
                            for (i in 0 until length) {
                                pc0[i] += srcL[i] * g
                                pc1[i] += srcR[i] * g
                            }
                        }
                    }
                } else {
                    for (c in input.indices) {
                        val pc = parallel[c]
                        val bc = buf[c]
                        for (i in 0 until length) pc[i] += bc[i] * g
                    }
                }
            }
        }

        if (hasEcho) {
            ensureEchoScratch(length)
            val elapsed = currentSample - echoTrigger.atSample
            val termDuration = echoTrigger.terminationRampSamples.coerceAtLeast(1)
            val termGain = (1.0f - (elapsed.toFloat() / termDuration)).coerceIn(0f, 1f)
            echoScratchL.fill(0f, 0, length)
            echoScratchR.fill(0f, 0, length)
            echoBuffer.readAndMix(echoTrigger.delaySamples, echoTrigger.feedback, echoScratchL, echoScratchR, 0, length, termGain)
        }
        val doBassProcessing = isRecipeActive && (
            recipe.strategy == TransitionStrategy.PHRASE_MATCHED_BASS_SWAP ||
            recipe.strategy == TransitionStrategy.SHORT_BASS_SWAP ||
            recipe.hasBassCuts
        )

        if (doBassProcessing) {
            val currentSample = mixer.currentXfadeSample
            val mainBassGain = recipe.bassGainAt(TrackTarget.OUTGOING, currentSample)
            val subBassGain = recipe.bassGainAt(TrackTarget.INCOMING, currentSample)
            scratchMainLow = ensureScratch(scratchMainLow, input.size, length)
            scratchMainHigh = ensureScratch(scratchMainHigh, input.size, length)
            scratchSubLow = ensureScratch(scratchSubLow, input.size, length)
            scratchSubHigh = ensureScratch(scratchSubHigh, input.size, length)

            val inL = input[0]
            val inR = if (input.size > 1) input[1] else input[0]
            crossoverMain.processTrack(
                inL, inR,
                scratchMainLow[0], if (input.size > 1) scratchMainLow[1] else scratchMainLow[0],
                scratchMainHigh[0], if (input.size > 1) scratchMainHigh[1] else scratchMainHigh[0],
                length, offset
            )

            val subL = sub[0]
            val subR = if (sub.size > 1) sub[1] else sub[0]
            crossoverSub.processTrack(
                subL, subR,
                scratchSubLow[0], if (sub.size > 1) scratchSubLow[1] else scratchSubLow[0],
                scratchSubHigh[0], if (sub.size > 1) scratchSubHigh[1] else scratchSubHigh[0],
                length, 0
            )

            for (c in input.indices) {
                val channel = input[c]
                val end = offset + length
                val mLow = scratchMainLow[c]
                val mHigh = scratchMainHigh[c]
                val sLow = scratchSubLow[c]
                val sHigh = scratchSubHigh[c]

                for (i in offset until end) {
                    val idx = i - offset
                    val mainAudio = mLow[idx] * mainBassGain + mHigh[idx]
                    val subAudio = sLow[idx] * subBassGain + sHigh[idx]
                    var sum = mainAudio * gains.main + subAudio * gains.sub
                    if (parallel != null) sum += parallel[c][idx]
                    channel[i] = softLimit(sum)
                }
            }
            return
        }

        for (c in input.indices) {
            val channel = input[c]
            val subChannel = sub[c]
            val end = offset + length
            val echoArr = if (c == 0) echoScratchL else echoScratchR
            for (i in offset until end) {
                var sum = channel[i] * gains.main + subChannel[i - offset] * gains.sub
                if (hasEcho) sum += echoArr[i - offset]
                if (parallel != null) sum += parallel[c][i - offset]
                channel[i] = softLimit(sum)
            }
        }
    }

    private fun ensureScratch(buffer: Array<FloatArray>, channels: Int, samples: Int): Array<FloatArray> {
        if (buffer.size == channels && buffer.all { it.size >= samples }) return buffer
        return Array(channels) { channel ->
            buffer.getOrNull(channel)?.takeIf { it.size >= samples } ?: FloatArray(samples)
        }
    }
    private fun ensureEchoScratch(samples: Int) {
        if (echoScratchL.size < samples) {
            echoScratchL = FloatArray(samples)
            echoScratchR = FloatArray(samples)
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

    override fun close() {
        // Lavaplayer closes a chain on hot-swap and track end: release the
        // feed so a replacement filter takes over without a silence gap.
        mixerProvider()?.retireFeeder(feederId)
    }

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

        private val FILTER_SEQ = java.util.concurrent.atomic.AtomicLong(0L)

        /** Knee start; audio below this passes through bit-identically. */
        private const val SOFT_LIMIT_THRESHOLD: Float = 0.9f

        @Volatile
        private var lastWarn: Long = 0L

        private fun now(): Long = System.currentTimeMillis()
    }
}
