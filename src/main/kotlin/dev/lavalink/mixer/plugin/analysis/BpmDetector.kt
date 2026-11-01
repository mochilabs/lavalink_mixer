package dev.lavalink.mixer.plugin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
 *     scoring in-range candidate is kept unless clustered adjacent onset spacing
 *     independently supports a shorter octave (avoiding integer-hop bias). [analyzeGrid] turns the refined
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
        var localPhaseMs = ((det.phaseHops * HOP_MS % periodMs) + periodMs) % periodMs
        if (localPhaseMs >= periodMs - HOP_MS) localPhaseMs = 0.0
        val phaseMs = windowStartMs + round(localPhaseMs).toLong()
        return BeatGrid(
            bpm = det.bpm,
            phaseMs = phaseMs,
            confidence = det.confidence,
        )
    }

    /** Scalar result of one detection pass, shared by both public entry points. */
    private class Detection(val bpm: Float, val confidence: Float, val firstBeatIdx: Int, val phaseHops: Double = 0.0)
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

        // Onset strength: fuse energy flux with 3-band spectral flux when available
        val energyFlux = FloatArray(n)
        energyFlux[0] = env[0]
        for (i in 1 until n) {
            val d = env[i] - env[i - 1]
            if (d > 0f) energyFlux[i] = d
        }

        val specFlux = if (sampleRate == 48000) SpectralFluxAnalyzer.computeSpectralFlux(frames) else null
        val flux = if (specFlux != null) {
            val (_, eStd) = stats(energyFlux, 0, n)
            val (_, sStd) = stats(specFlux, 0, specFlux.size)
            val invE = if (eStd > 1e-6f) 1f / eStd else 0f
            val invS = if (sStd > 1e-6f) 1f / sStd else 0f
            FloatArray(n) { i ->
                val ef = energyFlux[i] * invE
                val sf = if (i < specFlux.size) specFlux[i] * invS else 0f
                0.30f * ef + 0.70f * sf
            }
        } else {
            energyFlux
        }

        // Adaptive onset threshold over the whole window (silence + sparse
        // beats yields a low mean, so one sigma of headroom is enough to
        // separate real onsets from decode noise).
        val (mean, std) = stats(flux, 0, n)
        val threshold = mean + 1.5f * std
        if (threshold <= 0f) return null

        // First onset: detect from raw energy flux to preserve exact 10ms hop edge
        val (eMean, eStd) = stats(energyFlux, 0, n)
        val eThreshold = eMean + 1.5f * eStd
        var firstBeatIdx = -1
        for (i in 0 until n) {
            if (energyFlux[i] > eThreshold) {
                firstBeatIdx = i
                break
            }
        }
        val minLag = ((60_000.0 / maxBpm) / HOP_MS).toInt().coerceAtLeast(1)
        val maxLag = ((60_000.0 / minBpm) / HOP_MS).toInt().coerceAtMost(n / 2)
        if (minLag > maxLag) return null

        val (detrended, variance) = detrend(flux)
        if (variance <= 0.0) return null

        // Normalized autocorrelation across the lag range
        val scores = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            scores[lag] = normalizedCorrelation(detrended, variance, lag).toFloat()
        }

        // Global argmax of normalized autocorrelation
        var bestLag = -1
        var bestCorr = -1f
        for (lag in minLag..maxLag) {
            val s = scores[lag]
            if (s > bestCorr) {
                bestCorr = s
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null

        val onsets = extractOnsets(flux, threshold)

        val candidates = sortedSetOf(bestLag, bestLag * 2, round(bestLag / 2.0).toInt())
            .filter { it in minLag..maxLag }
            .map { it to sampleConfidence(detrended, variance, it) }
        if (candidates.isEmpty()) return null
        val maxConf = candidates.maxOf { it.second }
        val onsetPeriods = candidates.associate { (lag, _) -> lag to supportedOnsetPeriod(flux, threshold, lag) }
        val chosenLag = candidates.firstOrNull { (lag, _) -> onsetPeriods[lag] != null }?.first
            ?: candidates.firstOrNull { (_, conf) -> conf >= maxConf - 0.35f && conf >= 0.35f }?.first
            ?: bestLag
        val (eDetrended, eVariance) = detrend(energyFlux)
        val energyConf = sampleConfidence(eDetrended, eVariance, chosenLag)
        val specConf = sampleConfidence(detrended, variance, chosenLag)
        val rawConf = maxOf(energyConf, specConf)
        val chosenConf = if (onsets.size < 4) 0f else rawConf
        val chosenRefined = onsetPeriods[chosenLag] ?: refineLag(detrended, variance, chosenLag, n)
        val (phaseHops, _) = optimizePhaseFromOnsets(onsets, chosenRefined)
        val bpm = (60_000.0 / (chosenRefined * HOP_MS)).toFloat()
        return Detection(bpm, chosenConf, firstBeatIdx, phaseHops)
    }

    private fun supportedOnsetPeriod(flux: FloatArray, threshold: Float, lag: Int): Double? {
        val onsets = mutableListOf<Double>()
        var i = 0
        while (i < flux.size) {
            if (flux[i] <= threshold) { i++; continue }
            var weight = 0.0
            var position = 0.0
            while (i < flux.size && flux[i] > threshold) {
                weight += flux[i]
                position += i * flux[i].toDouble()
                i++
            }
            onsets += position / weight
        }
        val intervals = onsets.zipWithNext { first, second -> second - first }
        val supported = intervals.filter { abs(it - lag) <= 3.0 }
        if (supported.size < 4 || supported.size < intervals.size * 0.8) return null
        return supported.average()
    }

    private fun extractOnsets(flux: FloatArray, threshold: Float): List<Pair<Double, Double>> {
        val onsets = mutableListOf<Pair<Double, Double>>()
        var i = 0
        while (i < flux.size) {
            if (flux[i] <= threshold) {
                i++
                continue
            }
            var sumWeight = 0.0
            var sumPos = 0.0
            while (i < flux.size && flux[i] > threshold) {
                val w = (flux[i] - threshold).toDouble()
                sumWeight += w
                sumPos += i * w
                i++
            }
            if (sumWeight > 0.0) {
                onsets.add((sumPos / sumWeight) to sumWeight)
            }
        }
        return onsets
    }

    private fun optimizePhaseFromOnsets(onsets: List<Pair<Double, Double>>, periodHops: Double): Pair<Double, Float> {
        if (onsets.isEmpty() || periodHops <= 0.0) return 0.0 to 0f
        val totalWeight = onsets.sumOf { it.second }
        if (totalWeight <= 0.0) return 0.0 to 0f

        val tolerance = maxOf(2.5, periodHops * 0.20)
        var bestPhi = 0.0
        var bestScore = -1.0
        val steps = (periodHops * 10).toInt().coerceAtLeast(1)
        for (step in 0 until steps) {
            val phi = step * 0.1
            var score = 0.0
            for ((t, w) in onsets) {
                val offset = (t - phi) % periodHops
                val normOffset = (offset + periodHops) % periodHops
                val dist = minOf(normOffset, periodHops - normOffset)
                if (dist < tolerance) {
                    val cosVal = cos(Math.PI * dist / (2.0 * tolerance))
                    score += w * cosVal * cosVal
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestPhi = phi
            }
        }
        val alignmentRatio = (bestScore / totalWeight).coerceIn(0.0, 1.0).toFloat()
        return bestPhi to alignmentRatio
    }

    /**
     * Parabolic refinement of the autocorrelation peak at [bestLag]: the three
     * normalized neighbor scores are fitted with a parabola whose vertex gives the
     * sub-hop peak position, `delta = 0.5(a-c)/(a-2b+c)` in [-1, 1].
     * Requires strict concavity (denom < 0); falls back to integer lag when degenerate.
     */
    private fun refineLag(x: DoubleArray, variance: Double, bestLag: Int, n: Int): Double {
        val b = normalizedCorrelation(x, variance, bestLag)
        val a = if (bestLag > 1) normalizedCorrelation(x, variance, bestLag - 1) else b
        val c = if (bestLag + 1 < n) normalizedCorrelation(x, variance, bestLag + 1) else b
        val denom = a - 2.0 * b + c
        val scale = maxOf(abs(a), abs(b), abs(c), 1.0)
        if (denom >= -1e-9 * scale) return bestLag.toDouble()
        val delta = 0.5 * (a - c) / denom
        if (delta < -1.0 || delta > 1.0) return bestLag.toDouble()
        return bestLag + delta
    }

    private const val DETREND_HALF_WINDOW = 50

    private fun detrend(flux: FloatArray): Pair<DoubleArray, Double> {
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
        return x to variance
    }

    private fun normalizedCorrelation(x: DoubleArray, variance: Double, lag: Int): Double {
        val n = x.size
        val overlap = n - lag
        if (lag < 1 || overlap <= 0 || variance <= 0.0) return 0.0
        var cross = 0.0
        for (i in 0 until overlap) cross += x[i] * x[i + lag]
        return ((cross / overlap) / (variance / n)).coerceIn(0.0, 1.0)
    }

    private fun sampleConfidence(x: DoubleArray, variance: Double, lag: Int): Float {
        var best = 0.0
        for (l in (lag - 1)..(lag + 1)) {
            best = maxOf(best, normalizedCorrelation(x, variance, l))
        }
        return best.toFloat()
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