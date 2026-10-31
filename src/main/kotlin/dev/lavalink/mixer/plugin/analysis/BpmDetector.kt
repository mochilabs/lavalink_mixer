package dev.lavalink.mixer.plugin

import kotlin.math.sqrt

/**
 * Pure Kotlin tempo (BPM) + first-downbeat detection from decoded float PCM.
 * Operates on the same planar chunks the rest of the analysis pipeline
 * produces (`List<Array<FloatArray>>` = one array per channel, any length).
 *
 * Method (energy-flux onset detection + lag autocorrelation):
 *  1. Downmix to mono and fold into a ~100 frames/sec mean-square envelope.
 *  2. Half-wave-rectified first difference of the envelope = onset strength.
 *     A beat onset is a sharp *rise* in energy, so only positive flux counts;
 *     sustained loudness and decays contribute nothing.
 *  3. Autocorrelate the onset-strength signal over the lag window spanned by
 *     [minBpm]..[maxBpm]; the dominant lag is the beat period, from which the
 *     tempo follows directly.
 *  4. The first onset that clears an adaptive threshold (mean + 1.5 sigma) is
 *     the downbeat offset — the value a beat-matched crossfade actually needs,
 *     not just the scalar tempo.
 *  5. [BpmResult.confidence] scores how periodic the onset signal really is at
 *     the chosen lag, so a caller can tell "a beat grid exists" from "the
 *     autocorrelation had to pick *some* lag".
 *
 * No native code, no allocations beyond two small arrays sized by the scan
 * window (~3k frames for 30s @ 100fps); the per-lag autocorrelation is
 * O(n * lagRange), trivially cheap at these sizes.
 */
object BpmDetector {

    /**
     * Detected tempo and the offset (ms) of the first downbeat from buffer start.
     *
     * [confidence] is in 0..1: the normalized autocorrelation of the (mean-
     * removed) onset signal at the beat lag. ~0 means the onsets show no
     * periodicity at that lag (ambient/noise/sparse), ~1 means a steady pulse.
     * It measures *whether a pulse exists*, not whether [bpm] is the right
     * metrical level: a half- or double-time estimate of a real pulse still
     * scores high. A null [BpmResult] (no usable signal) stays distinct from a
     * low-confidence one.
     */
    data class BpmResult(val bpm: Float, val firstBeatMs: Long, val confidence: Float)

    /**
     * Frames-per-second of the envelope. 100 fps = 10ms resolution, enough to
     * place a 120 BPM beat to ±1ms after conversion.
     */
    private const val FRAMES_PER_SECOND = 100

    fun analyze(
        frames: List<Array<FloatArray>>,
        sampleRate: Int = 48000,
        minBpm: Float = 60f,
        maxBpm: Float = 200f,
    ): BpmResult? {
        val env = envelope(frames, sampleRate)
        val n = env.size
        // Need at least one full beat period of the slowest tempo to correlate.
        if (n < 8) return null

        // Onset strength: half-wave-rectified first difference. Seed flux[0]
        // with the opening envelope so a track that starts on its downbeat
        // (no lead-in silence) still registers a rise at t=0.
        val flux = FloatArray(n)
        flux[0] = env[0]
        for (i in 1 until n) {
            val d = env[i] - env[i - 1]
            if (d > 0f) flux[i] = d
        }

        // Adaptive onset threshold over the whole window (silence + sparse
        // beats yields a low mean, so one sigma of headroom is enough to
        // separate real onsets from decode noise).
        val (mean, std) = stats(flux, 0, n)
        val threshold = mean + 1.5f * std
        if (threshold <= 0f) return null

        var firstBeatIdx = -1
        for (i in 0 until n) {
            if (flux[i] > threshold) {
                firstBeatIdx = i
                break
            }
        }

        val hopSec = 1.0 / FRAMES_PER_SECOND
        val minLag = ((60.0 / maxBpm) / hopSec).toInt().coerceAtLeast(1)
        val maxLag = ((60.0 / minBpm) / hopSec).toInt().coerceAtMost(n / 2)
        if (minLag > maxLag) return null

        var bestLag = -1
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (i in 0 until n - lag) {
                score += flux[i] * flux[i + lag].toDouble()
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null

        val bpm = (60.0 / (bestLag * hopSec)).toFloat()
        val firstBeatMs = if (firstBeatIdx >= 0) (firstBeatIdx * hopSec * 1000.0).toLong() else 0L
        return BpmResult(bpm, firstBeatMs, beatConfidence(flux, bestLag))
    }

    /**
     * Half-width (frames) of the local-mean window removed from the onset
     * signal before scoring: +-0.5s. Slow loudness swells (pads, ambient)
     * make a smooth onset signal that correlates with itself at *every* short
     * lag; subtracting a local mean leaves only energy changes faster than
     * that, i.e. actual pulses.
     */
    private const val DETREND_HALF_WINDOW = 50

    /**
     * Normalized autocorrelation of the detrended onset signal at [lag]
     * (best of lag-1..lag+1: a 10ms-quantised lag cannot hold a human-timed
     * pulse exactly), clamped to 0..1. Each side is divided by its own term
     * count so a longer lag (fewer overlapping frames) is not penalised, and
     * variance is normalised out so loudness never changes the score.
     */
    private fun beatConfidence(flux: FloatArray, lag: Int): Float {
        val n = flux.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + flux[i]
        val x = DoubleArray(n)
        var variance = 0.0
        for (i in 0 until n) {
            val lo = maxOf(0, i - DETREND_HALF_WINDOW)
            val hi = minOf(n - 1, i + DETREND_HALF_WINDOW)
            val localMean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            x[i] = flux[i] - localMean
            variance += x[i] * x[i]
        }
        if (variance <= 0.0) return 0f
        var best = 0.0
        for (l in (lag - 1)..(lag + 1)) {
            val overlap = n - l
            if (l < 1 || overlap <= 0) continue
            var cross = 0.0
            for (i in 0 until overlap) cross += x[i] * x[i + l]
            best = maxOf(best, (cross / overlap) / (variance / n))
        }
        return best.coerceIn(0.0, 1.0).toFloat()
    }

    /** Mono mean-square envelope, one value per ~10ms hop. */
    private fun envelope(frames: List<Array<FloatArray>>, sampleRate: Int): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val hop = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)
        val channels = frames.maxOf { it.size }
        var total = 0L
        for (f in frames) total += f[0].size
        val out = FloatArray(((total + hop - 1) / hop).toInt())
        var oi = 0
        var acc = 0.0
        var cnt = 0
        for (f in frames) {
            val len = f[0].size
            for (i in 0 until len) {
                var e = 0.0
                for (c in f.indices) {
                    val s = f[c][i].toDouble()
                    e += s * s
                }
                if (channels > 1) e /= channels
                acc += e
                if (++cnt == hop) {
                    out[oi++] = (acc / cnt).toFloat()
                    acc = 0.0
                    cnt = 0
                }
            }
        }
        if (cnt > 0 && oi < out.size) out[oi] = (acc / cnt).toFloat()
        return out
    }

    private fun stats(a: FloatArray, from: Int, to: Int): Pair<Float, Float> {
        var mean = 0.0
        for (i in from until to) mean += a[i]
        mean /= (to - from)
        var varAcc = 0.0
        for (i in from until to) {
            val d = a[i] - mean
            varAcc += d * d
        }
        val std = sqrt(varAcc / (to - from))
        return mean.toFloat() to std.toFloat()
    }
}
