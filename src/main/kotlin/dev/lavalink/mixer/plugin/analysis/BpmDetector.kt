package dev.lavalink.mixer.plugin

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Pure Kotlin tempo (BPM) + beat-grid detection from decoded float PCM.
 * Operates on the same planar chunks the rest of the analysis pipeline
 * produces (`List<Array<FloatArray>>` = one array per channel, any length).
 *
 * Method (energy-flux onset detection + lag autocorrelation):
 *  1. Downmix to mono and fold into a ~100 frames/sec mean-square envelope.
 *  2. Half-wave-rectified first difference of the envelope = onset strength.
 *     A beat onset is a sharp *rise* in energy, so only positive flux counts;
 *     sustained loudness and decays contribute nothing.
 *  3. Autocorrelate the onset-strength signal over the lag window spanned by
 *     [minBpm]..[maxBpm]. A plain argmax only quantizes the period to whole
 *     10ms hops (~2% tempo resolution), so the winning peak is refined by
 *     parabolic interpolation across its raw-score neighbours; and a raw
 *     argmax alone can lock onto a half/double-tempo octave multiple, so the
 *     x2 and x0.5 harmonics of that lag are rescored with the same
 *     normalized-autocorrelation confidence as the winner and the best-
 *     scoring in-range candidate is kept. [analyzeGrid] turns the refined
 *     period + first onset into a phase-anchored [BeatGrid];
 *     [analyze] keeps the legacy scalar shape.
 *  4. The first onset that clears an adaptive threshold (mean + 1.5 sigma)
 *     anchors the grid phase in [BeatGrid.phaseMs] / [BpmResult.firstBeatMs].
 *  5. [BpmResult.confidence] scores how periodic the onset signal really is at
 *     the chosen lag, so a caller can tell "a beat grid exists" from "the
 *     autocorrelation had to pick *some* lag".
 *
 * No native code, no allocations beyond a few small arrays sized by the scan
 * window (~3k frames for 30s @ 100fps); the per-lag autocorrelation is
 * O(n * lagRange), trivially cheap at these sizes.
 */
object BpmDetector {

    /**
     * Legacy scalar view of [analyzeGrid] (kept for [TrackAnalysisService.measureBpmBlocking]).
     *
     * [firstBeatMs] is the offset of the FIRST ONSET (adaptive-threshold crossing),
     * not the grid phase — a track that opens mid-bar reports where sound first
     * rises, not where a beat grid puts the boundary. [analyzeGrid] carries the
     * grid-aligned phase instead.
     *
     * [confidence] is in 0..1: the normalized autocorrelation of the (mean-
     * removed) onset signal at the beat lag. ~0 means the onsets show no
     * periodicity at that lag (ambient/noise/sparse), ~1 means a steady pulse.
     * It measures *whether a pulse exists*, not whether [bpm] is the right
     * metrical level: a half- or double-time estimate of a real pulse still
     * scores high. A null [BpmResult] (no usable signal) stays distinct from a
     * low-confidence one.
     */
    data class BpmResult(val bpm: Float, val firstBeatMs: Long, val confidence: Float) {
        val phaseMs: Long get() = firstBeatMs
    }
    /**
     * Frames-per-second of the envelope. 100 fps = 10ms resolution; parabolic
     * refinement recovers sub-hop period precision on top of that.
     */
    private const val FRAMES_PER_SECOND = 100

    /** Hop length of the envelope, in ms. */
    private const val HOP_MS = 1000.0 / FRAMES_PER_SECOND

    /**
     * Confidence band within which two octave lags count as an equally
     * supported metrical level, and the faster tempo (smaller lag) wins.
     * Sized so float noise between octaves (~3e-4 for a perfectly periodic
     * pulse) is a tie, but real octave discrimination (~0.04+ with humanized
     * timing) is not.
     */
    private const val OCTAVE_TIE_EPS = 0.02f

    /**
     * A tempo estimate below this confidence is reported as-is (legacy callers
     * gate on it themselves); [analyzeGrid] simply forwards it — the caller's
     * beat-match gate, not the detector, decides what counts as "a pulse".
     */
    fun analyze(
        frames: List<Array<FloatArray>>,
        sampleRate: Int = 48000,
        minBpm: Float = 60f,
        maxBpm: Float = 200f,
    ): BpmResult? {
        val det = detect(frames, sampleRate, minBpm, maxBpm) ?: return null
        val firstBeatMs = if (det.firstBeatIdx >= 0) (det.firstBeatIdx * HOP_MS).toLong() else 0L
        return BpmResult(det.bpm, firstBeatMs, det.confidence)
    }

    /**
     * Detects a beat grid: refined tempo, grid phase (offset of the first beat
     * landing within one period), and pulse confidence. Returns null when the
     * window is too short or the onset signal too weak to correlate, never on
     * a low-confidence pulse — the gate belongs to the caller.
     */
    fun analyzeGrid(
        frames: List<Array<FloatArray>>,
        sampleRate: Int = 48000,
        minBpm: Float = 60f,
        maxBpm: Float = 200f,
        windowStartMs: Long = 0L,
    ): BeatGrid? {
        val det = detect(frames, sampleRate, minBpm, maxBpm) ?: return null
        val periodMs = 60_000.0 / det.bpm
        val firstOnsetMs = if (det.firstBeatIdx >= 0) det.firstBeatIdx * HOP_MS else 0.0
        // Grid phase: the pulse's first landing within one period. Anchoring the
        // grid at `firstOnsetMs mod period` makes a grid beat land exactly on
        // the first onset (and every period thereafter). NOT a musical downbeat:
        // no bar/section awareness, just the phase where the periodic pulse sits.
        val localPhaseMs = ((firstOnsetMs % periodMs) + periodMs) % periodMs
        val phaseMs = windowStartMs + round(localPhaseMs).toLong()
        return BeatGrid(
            bpm = det.bpm,
            phaseMs = phaseMs,
            confidence = det.confidence,
        )
    }

    /** Scalar result of one detection pass, shared by both public entry points. */
    private class Detection(val bpm: Float, val confidence: Float, val firstBeatIdx: Int)

    private fun detect(
        frames: List<Array<FloatArray>>,
        sampleRate: Int,
        minBpm: Float,
        maxBpm: Float,
    ): Detection? {
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

        val minLag = ((60_000.0 / maxBpm) / HOP_MS).toInt().coerceAtLeast(1)
        val maxLag = ((60_000.0 / minBpm) / HOP_MS).toInt().coerceAtMost(n / 2)
        if (minLag > maxLag) return null

        var bestLag = -1
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val score = rawScore(flux, lag, n)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null

        // Octave pass: multiples of the true period correlate just as strongly,
        // so a raw argmax can lock onto the half-tempo (or double-tempo) octave.
        // Rescore base / x2 / x0.5 with the same normalized autocorrelation used
        // for confidence and keep the best-scoring in-range candidate. For a
        // strictly periodic pulse the score is near-identical across octaves
        // (float noise would otherwise flip the winner), so when candidates sit
        // within [OCTAVE_TIE_EPS] of the best score the SMALLEST lag — the
        // fastest tempo — wins: a true 120 BPM pulse then keeps lag 50 over
        // 100, while a true 60 BPM pulse keeps 100 because conf at half the
        // lag is ~0 (no onset pair correlates there). Confidence always uses
        // the integer lag (it already samples lag-1..lag+1); the reported
        // tempo comes from the interpolated refinement of the chosen lag.
        var maxConf = 0f
        val candidates = sortedSetOf(bestLag, bestLag * 2, round(bestLag / 2.0).toInt())
            .filter { it >= 1 && it < n }
            .map { it to beatConfidence(flux, it) }
            .filter { (lag, _) ->
                val tempo = 60_000.0 / (lag * HOP_MS)
                tempo in minBpm..maxBpm
            }
        if (candidates.isEmpty()) return null
        for ((_, conf) in candidates) maxConf = maxOf(maxConf, conf)
        val chosenLag = candidates.first { (_, conf) -> conf >= maxConf - OCTAVE_TIE_EPS }.first
        val chosenConf = beatConfidence(flux, chosenLag)
        val chosenRefined = refineLag(flux, chosenLag, n)

        val bpm = (60_000.0 / (chosenRefined * HOP_MS)).toFloat()
        return Detection(bpm, chosenConf, firstBeatIdx)
    }

    /** Raw (unnormalized) autocorrelation of the onset signal at one lag. */
    private fun rawScore(flux: FloatArray, lag: Int, n: Int): Double {
        var s = 0.0
        for (i in 0 until n - lag) {
            s += flux[i] * flux[i + lag].toDouble()
        }
        return s
    }

    /**
     * Parabolic refinement of the autocorrelation peak at [bestLag]: the three
     * raw neighbour scores are fitted with a parabola whose vertex gives the
     * sub-hop peak position, `delta = 0.5(a-c)/(a-2b+c)` in [-1, 1]. Falls back
     * to the untouched integer lag when the peak is degenerate (flat or
     * monotonically rising edge) — interpolation must never *worsen* the
     * argmax estimate.
     */
    private fun refineLag(flux: FloatArray, bestLag: Int, n: Int): Double {
        val b = rawScore(flux, bestLag, n)
        val a = if (bestLag > 1) rawScore(flux, bestLag - 1, n) else b
        val c = if (bestLag + 1 < n) rawScore(flux, bestLag + 1, n) else b
        val denom = a - 2.0 * b + c
        val scale = maxOf(abs(a), abs(b), abs(c), 1.0)
        if (abs(denom) < 1e-9 * scale) return bestLag.toDouble()
        val delta = 0.5 * (a - c) / denom
        if (delta < -1.0 || delta > 1.0) return bestLag.toDouble()
        return bestLag + delta
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