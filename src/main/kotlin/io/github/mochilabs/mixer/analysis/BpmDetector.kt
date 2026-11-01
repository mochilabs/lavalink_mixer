package io.github.mochilabs.mixer

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
    private val log = org.slf4j.LoggerFactory.getLogger(BpmDetector::class.java)

    internal data class Onset(val hop: Double, val weight: Double)


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
        val localPhaseMs = ((det.phaseHops * HOP_MS % periodMs) + periodMs) % periodMs
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
        energyFlux[0] = 0f
        for (i in 1 until n) {
            val d = env[i] - env[i - 1]
            if (d > 0f) energyFlux[i] = d
        }

        val specFlux = try {
            SpectralFluxAnalyzer.computeSpectralFlux(frames, sampleRate)
        } catch (e: Exception) {
            log.warn("Spectral flux computation failed, falling back to energy flux: ${e.message}")
            null
        }
        val (_, eStd) = stats(energyFlux, 0, n)
        val (_, sStd) = if (specFlux != null) stats(specFlux, 0, specFlux.size) else (0f to 0f)
        if (eStd < 0.005f && sStd < 0.005f) return null
        val flux = if (specFlux != null) {
            val invE = if (eStd > 1e-4f) 1f / eStd else 0f
            val invS = if (sStd > 1e-4f) 1f / sStd else 0f
            FloatArray(n) { i ->
                val ef = energyFlux[i] * invE
                val sf = if (i < specFlux.size) specFlux[i] * invS else 0f
                0.30f * ef + 0.70f * sf
            }
        } else {
            energyFlux
        }
        val (gMean, gStd) = stats(flux, 0, n)
        if (gStd <= 1e-4f) return null

        // 5-second sliding window adaptive threshold (local mean + 1.5 * local std)
        val prefix = DoubleArray(n + 1)
        val prefixSq = DoubleArray(n + 1)
        for (i in 0 until n) {
            val v = flux[i].toDouble()
            prefix[i + 1] = prefix[i] + v
            prefixSq[i + 1] = prefixSq[i] + v * v
        }
        val halfWindow = 250 // 2.5s on each side = 5.0s window
        val minThreshold = maxOf(gMean + 0.5f * gStd, 0.05f)
        val threshold = FloatArray(n) { i ->
            val lo = maxOf(0, i - halfWindow)
            val hi = minOf(n - 1, i + halfWindow)
            val len = hi - lo + 1
            val m = (prefix[hi + 1] - prefix[lo]) / len
            val v = maxOf(0.0, (prefixSq[hi + 1] - prefixSq[lo]) / len - m * m)
            val s = sqrt(v)
            maxOf((m + 1.5 * s).toFloat(), minThreshold)
        }

        val (eGMean, eGStd) = stats(energyFlux, 0, n)
        val ePrefix = DoubleArray(n + 1)
        val ePrefixSq = DoubleArray(n + 1)
        for (i in 0 until n) {
            val v = energyFlux[i].toDouble()
            ePrefix[i + 1] = ePrefix[i] + v
            ePrefixSq[i + 1] = ePrefixSq[i] + v * v
        }
        val eMinThreshold = maxOf(eGMean + 0.5f * eGStd, 0.005f)
        val eThreshold = FloatArray(n) { i ->
            val lo = maxOf(0, i - halfWindow)
            val hi = minOf(n - 1, i + halfWindow)
            val len = hi - lo + 1
            val m = (ePrefix[hi + 1] - ePrefix[lo]) / len
            val v = maxOf(0.0, (ePrefixSq[hi + 1] - ePrefixSq[lo]) / len - m * m)
            val s = sqrt(v)
            maxOf((m + 1.5 * s).toFloat(), eMinThreshold)
        }

        // First onset detected from raw energy flux to preserve exact 10ms hop edge without FFT lookahead
        var firstBeatIdx = -1
        for (i in 0 until n) {
            if (energyFlux[i] > eThreshold[i]) {
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

        val rawCandidates = mutableSetOf<Int>()
        rawCandidates.add(bestLag)
        rawCandidates.add(bestLag * 2)
        rawCandidates.add(round(bestLag / 2.0).toInt())

        // Ensure both half-lags and double-lags are present so odd bestLag (e.g. 75 -> 38)
        // has its 2x multiple (76) available for cross-checking without omission.
        val expandedLags = mutableSetOf<Int>()
        for (l in rawCandidates) {
            if (l in minLag..maxLag) expandedLags.add(l)
            if (l * 2 in minLag..maxLag) expandedLags.add(l * 2)
        }
        val candidateLags = expandedLags.filter { it in minLag..maxLag }.toSortedSet()
        if (candidateLags.isEmpty()) return null

        val candidates = candidateLags.map { it to sampleConfidence(detrended, variance, it) }
        val maxConf = candidates.maxOf { it.second }

        val onsetSupport = candidates.associate { (lag, _) ->
            val autoRefined = refineLag(detrended, variance, lag, minLag, maxLag, n)
            lag to supportedOnsetPeriod(onsets, lag, autoRefined)
        }

        // Octave disambiguation:
        // Rank candidate lags with onset support, combining autocorrelation confidence and tempo prior.
        // For candidate lag L, search for double lag 2L within +-1 hop (e.g. lag=38, double=75 or 76).
        val variation = onsetVariation(onsets)
        var chosenLag = -1
        for ((lag, conf) in candidates) {
            val supp = onsetSupport[lag] ?: continue
            val doubleEntry = candidates.firstOrNull { abs(it.first - 2 * lag) <= 1 }
            val doubleLag = doubleEntry?.first
            val doubleConf = doubleEntry?.second ?: 0f
            val doubleSupp = doubleLag?.let { onsetSupport[it] }
            val doubleBpm = doubleLag?.let { (60_000.0 / (it * HOP_MS)).toFloat() } ?: 0f

            // 1-b: Prefer doubleLag if doubleLag is supported by onsets, sits in valid beat tempo (66..115 BPM),
            // has strong autocorrelation (conf(2L) >= conf(L) * 0.88), and onsets show realistic drum variation
            // (e.g. kick/snare vs hi-hats where CV >= 0.16).
            // If onsets are uniform clicks (e.g. non-integer click track where CV < 0.16), candidate lag wins.
            if (doubleSupp != null && doubleBpm in 66f..115f && doubleConf >= conf * 0.88f && variation >= 0.16f) {
                chosenLag = doubleLag
                break
            } else {
                chosenLag = lag
                break
            }
        }
        if (chosenLag < 0) {
            chosenLag = candidates.maxByOrNull { (lag, conf) ->
                val bpm = (60_000.0 / (lag * HOP_MS)).toFloat()
                conf * tempoPrior(bpm)
            }?.first ?: bestLag
        }

        val (eDetrended, eVariance) = detrend(energyFlux)
        // Fail-closed if broadband energy flux variance is negligible (pure tone, white noise, static drone)
        if (eVariance / n < 4e-5) return null
        val energyConf = sampleConfidence(eDetrended, eVariance, chosenLag)
        val specConf = sampleConfidence(detrended, variance, chosenLag)
        val rawConf = maxOf(energyConf, specConf)
        val chosenConf = if (onsets.size < 4) 0f else rawConf

        val chosenRefined = onsetSupport[chosenLag]?.period ?: refineLag(detrended, variance, chosenLag, minLag, maxLag, n)
        val (refinedPeriod, phaseHops, _) = optimizePhaseAndPeriod(onsets, chosenRefined)
        val bpm = (60_000.0 / (refinedPeriod * HOP_MS)).toFloat()
        return Detection(bpm, chosenConf, firstBeatIdx, phaseHops)
    }

    private data class SupportedPeriod(val period: Double, val totalWeight: Double)

    private fun supportedOnsetPeriod(
        onsets: List<Onset>,
        lag: Int,
        autoRefinedLag: Double,
    ): SupportedPeriod? {
        if (onsets.size < 4) return null
        val tol = maxOf(1.5, lag * 0.05)

        val matchedIntervals = mutableListOf<Double>()
        val matchedWeights = mutableListOf<Double>()

        for (i in onsets.indices) {
            val o1 = onsets[i]
            for (j in (i + 1) until onsets.size) {
                val o2 = onsets[j]
                val diff = o2.hop - o1.hop
                if (diff > lag + tol) break
                if (abs(diff - lag) <= tol) {
                    matchedIntervals.add(diff)
                    matchedWeights.add(minOf(o1.weight, o2.weight))
                }
            }
        }

        if (matchedIntervals.size < 4) return null
        val totalSupportedWeight = matchedWeights.sum()

        // Weighted median of matched intervals
        val sortedPairs = matchedIntervals.indices.map { matchedIntervals[it] to matchedWeights[it] }
            .sortedBy { it.first }
        val halfWeight = totalSupportedWeight / 2.0
        var acc = 0.0
        var median = sortedPairs.first().first
        for ((d, w) in sortedPairs) {
            acc += w
            if (acc >= halfWeight) {
                median = d
                break
            }
        }

        val period = if (abs(median - autoRefinedLag) > autoRefinedLag * 0.05) {
            autoRefinedLag
        } else {
            median
        }
        return SupportedPeriod(period, totalSupportedWeight)
    }
    private fun onsetVariation(onsets: List<Onset>): Float {
        if (onsets.size < 4) return 0f
        var sum = 0.0
        for (o in onsets) sum += o.weight
        val mean = sum / onsets.size
        if (mean <= 0.0) return 0f
        var varAcc = 0.0
        for (o in onsets) {
            val d = o.weight - mean
            varAcc += d * d
        }
        return (sqrt(varAcc / onsets.size) / mean).toFloat()
    }


    private fun extractOnsets(flux: FloatArray, threshold: FloatArray): List<Onset> {
        val onsets = mutableListOf<Onset>()
        var i = 0
        while (i < flux.size) {
            if (flux[i] <= threshold[i]) {
                i++
                continue
            }
            var sumWeight = 0.0
            var sumPos = 0.0
            while (i < flux.size && flux[i] > threshold[i]) {
                val w = ((flux[i] - threshold[i]) / threshold[i]).toDouble()
                sumWeight += w
                sumPos += i * w
                i++
            }
            if (sumWeight > 0.0) {
                // Compensate for 2048 FFT Hann window lookahead (~0.5 hops) to center transient
                onsets.add(Onset(sumPos / sumWeight + 0.5, sumWeight))
            }
        }
        return onsets
    }

    private fun optimizePhaseAndPeriod(onsets: List<Onset>, initialPeriod: Double): Triple<Double, Double, Float> {
        if (onsets.isEmpty() || initialPeriod <= 0.0) return Triple(initialPeriod, 0.0, 0f)
        val totalWeight = onsets.sumOf { it.weight }
        if (totalWeight <= 0.0) return Triple(initialPeriod, 0.0, 0f)

        val tolerance = maxOf(1.5, initialPeriod * 0.10)
        var bestPhi = 0.0
        var bestScore = -1.0
        val steps = (initialPeriod * 10).toInt().coerceAtLeast(1)
        for (step in 0 until steps) {
            val phi = step * 0.1
            var score = 0.0
            for (o in onsets) {
                val offset = (o.hop - phi) % initialPeriod
                val normOffset = (offset + initialPeriod) % initialPeriod
                val dist = minOf(normOffset, initialPeriod - normOffset)
                if (dist < tolerance) {
                    val cosVal = cos(Math.PI * dist / (2.0 * tolerance))
                    score += o.weight * cosVal * cosVal
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestPhi = phi
            }
        }
        val alignmentRatio = (bestScore / totalWeight).coerceIn(0.0, 1.0).toFloat()

        // Jointly refine period and phase using weighted least squares (WLS) across the full window
        val (refinedPeriod, refinedPhi) = refinePeriodAndPhaseWls(onsets, initialPeriod, bestPhi)
        return Triple(refinedPeriod, refinedPhi, alignmentRatio)
    }

    private fun refinePeriodAndPhaseWls(
        onsets: List<Onset>,
        initialPeriod: Double,
        initialPhase: Double,
    ): Pair<Double, Double> {
        if (onsets.size < 4 || initialPeriod <= 0.0) return initialPeriod to initialPhase

        var currPeriod = initialPeriod
        var currPhase = initialPhase
        val toleranceRatios = doubleArrayOf(0.22, 0.12, 0.08)

        for (tolRatio in toleranceRatios) {
            val tol = maxOf(1.2, currPeriod * tolRatio)
            var sumW = 0.0
            var sumK = 0.0
            var sumT = 0.0
            val valid = mutableListOf<Triple<Double, Double, Double>>() // (k, t, w)
            val beatIndices = mutableSetOf<Long>()

            for (onset in onsets) {
                val k = round((onset.hop - currPhase) / currPeriod)
                val expected = currPhase + k * currPeriod
                if (abs(onset.hop - expected) <= tol) {
                    valid.add(Triple(k, onset.hop, onset.weight))
                    beatIndices.add(k.toLong())
                    sumW += onset.weight
                    sumK += onset.weight * k
                    sumT += onset.weight * onset.hop
                }
            }

            if (valid.size < 4 || beatIndices.size < 3 || sumW <= 0.0) {
                break
            }

            val meanK = sumK / sumW
            val meanT = sumT / sumW

            var num = 0.0
            var den = 0.0
            for ((k, t, w) in valid) {
                val dk = k - meanK
                val dt = t - meanT
                num += w * dk * dt
                den += w * dk * dk
            }

            if (den <= 1e-9) break
            val refinedPeriod = num / den
            // Allow up to 3% refinement from initial period
            if (abs(refinedPeriod - initialPeriod) > initialPeriod * 0.03) {
                break
            }
            val refinedPhase = meanT - refinedPeriod * meanK
            currPeriod = refinedPeriod
            currPhase = ((refinedPhase % currPeriod) + currPeriod) % currPeriod
        }

        return currPeriod to currPhase
    }

    private fun tempoPrior(bpm: Float): Float {
        val log2Ratio = (ln(bpm.toDouble() / 120.0) / ln(2.0)).toFloat()
        return exp(-0.5f * (log2Ratio / 0.90f) * (log2Ratio / 0.90f))
    }

    /**
     * Parabolic refinement of the autocorrelation peak at [bestLag]: the three
     * normalized neighbor scores are fitted with a parabola whose vertex gives the
     * sub-hop peak position, `delta = 0.5(a-c)/(a-2b+c)` in [-0.5, 0.5].
     * Requires strict concavity (denom < 0); falls back to integer lag when degenerate
     * or when [bestLag] sits on the boundary.
     */
    private fun refineLag(x: DoubleArray, variance: Double, bestLag: Int, minLag: Int, maxLag: Int, n: Int): Double {
        if (bestLag <= minLag || bestLag >= maxLag) return bestLag.toDouble()
        val b = normalizedCorrelation(x, variance, bestLag)
        val a = normalizedCorrelation(x, variance, bestLag - 1)
        val c = normalizedCorrelation(x, variance, bestLag + 1)
        val denom = a - 2.0 * b + c
        val scale = maxOf(abs(a), abs(b), abs(c), 1.0)
        if (denom >= -1e-9 * scale) return bestLag.toDouble()
        val delta = 0.5 * (a - c) / denom
        if (delta < -0.5 || delta > 0.5) return bestLag.toDouble()
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