package io.github.mochilabs.mixer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Tempo and local beat-grid detection from planar PCM on the isolated analysis path.
 * A mean-normalized energy envelope is fused with channel-independent spectral flux.
 * Local autocorrelation peaks and octave hypotheses compete using duration-aware accents
 * and a log-tempo prior. A cosine phase search and WLS refine the selected period;
 * integer BPM is accepted only when the same assigned onsets support its residual.
 *
 * [analyze] reports the first energy onset, while [analyzeGrid] reports the fitted
 * grid phase in track coordinates. Confidence measures periodicity, not certainty
 * about the musical metrical level. See docs/bpm-detector-design.md for references,
 * measured policies and evaluation limits. This is not a full BeatRoot/DP tracker.
 */
object BpmDetector {
    private val log = org.slf4j.LoggerFactory.getLogger(BpmDetector::class.java)

    private data class Onset(val hop: Double, val weight: Double, val salience: Double = weight)

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

    /** Relative energy-flux mean square; provisional until real-track calibration. */
    private const val MIN_REL_FLUX_MS = 0.02
    // 105 BPM collapsed the 150 BPM regression to 75; retain 120 pending music calibration.
    private const val PRIOR_CENTER_BPM = 120.0
    private const val PRIOR_SIGMA_OCT = 1.0
    /** Fixed analysis level before log compression, independent of playback gain. */
    private const val SPECTRAL_REFERENCE_RMS = 0.1
    /** Measured with 2048/Hann kicks at 44.1/48 kHz over all ten sub-hop offsets. */
    private const val SPECTRAL_ONSET_BIAS_HOPS = 1.15
    /** Include the attack preceding a flux centroid when measuring its duration. */
    private const val ACCENT_PREROLL_HOPS = 2.0
    /** Pairwise subdivision check; measured on synthetic patterns, see docs/bpm-detector-design.md. */
    private const val SUBDIVISION_CONTRAST_CENTER = 0.48
    private const val SUBDIVISION_CONTRAST_GAIN = 15.0
    private const val SUBDIVISION_PRIOR_SIGMA_OCT = 0.5
    private const val SUBDIVISION_PERIOD_TOLERANCE = 0.03
    private const val SUBDIVISION_MIN_ACCENT_RATIO = 0.8f
    private const val SUBDIVISION_MIN_OFF_SHARE = 0.25
    private const val SUBDIVISION_MIN_FAST_BPM = 155.0
    private const val SNAP_MAX_DELTA_BPM = 0.15
    private const val SNAP_MIN_ONSETS = 8
    private const val SNAP_MIN_BEAT_SPAN = 12
    private const val SNAP_RMS_FACTOR = 1.2
    private const val SNAP_RMS_SLACK_HOPS = 0.1

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
    fun analyzeExtendedGrid(
        frames: List<Array<FloatArray>>,
        sampleRate: Int = 48000,
        minBpm: Float = 60f,
        maxBpm: Float = 200f,
        windowStartMs: Long = 0L,
    ): ExtendedBeatGrid? {
        val det = detect(frames, sampleRate, minBpm, maxBpm) ?: return null
        val periodSamples = (sampleRate.toDouble() * 60.0) / det.bpm.toDouble()
        val hopSamples = (sampleRate.toDouble() * HOP_MS / 1000.0)
        val windowStartSamples = Math.round(windowStartMs.toDouble() * sampleRate.toDouble() / 1000.0)

        val rawPhaseSample = Math.round(det.phaseHops * hopSamples)
        val periodSamplesL = Math.round(periodSamples)
        val phaseSampleLocal = if (periodSamplesL > 0) ((rawPhaseSample % periodSamplesL) + periodSamplesL) % periodSamplesL else 0L
        val phaseSample = windowStartSamples + phaseSampleLocal

        val rawDownbeatSample = Math.round(det.downbeatHopOffset * hopSamples)
        val barSamplesL = periodSamplesL * 4L
        val downbeatSampleLocal = if (barSamplesL > 0) ((rawDownbeatSample % barSamplesL) + barSamplesL) % barSamplesL else phaseSampleLocal
        val downbeatSample = windowStartSamples + downbeatSampleLocal

        return ExtendedBeatGrid(
            bpm = det.bpm,
            phaseSample = phaseSample,
            bpmConfidence = det.confidence,
            downbeatSample = downbeatSample,
            downbeatConfidence = det.downbeatConfidence,
            phraseConfidence = 0.0f,
            isNon44Meter = det.isNon44Meter,
            sampleRate = sampleRate,
        )
    }


    /** Scalar result of one detection pass, shared by both public entry points. */
    private class Detection(
        val bpm: Float,
        val confidence: Float,
        val firstBeatIdx: Int,
        val phaseHops: Double = 0.0,
        val downbeatHopOffset: Double = 0.0,
        val downbeatConfidence: Float = 0.0f,
        val isNon44Meter: Boolean = false,
    )
    private fun detect(
        frames: List<Array<FloatArray>>,
        sampleRate: Int,
        minBpm: Float,
        maxBpm: Float,
    ): Detection? {
        val env = envelope(frames, sampleRate)
        val n = env.size
        // The lag search below also requires two periods of the candidate tempo.
        if (n < 8) return null

        val meanEnv = env.sumOf { it.toDouble() } / n
        if (meanEnv < 1e-9) return null
        val invMean = (1.0 / meanEnv).toFloat()
        for (i in env.indices) env[i] *= invMean

        // Onset strength: fuse energy flux with 3-band spectral flux when available
        val energyFlux = FloatArray(n)
        energyFlux[0] = 0f
        for (i in 1 until n) {
            val d = env[i] - env[i - 1]
            if (d > 0f) energyFlux[i] = d
        }
        val (eDetrended, eVariance) = detrend(energyFlux)
        log.debug("BPM relative energy-flux mean square: {} (minimum {})", eVariance / n, MIN_REL_FLUX_MS)
        if (eVariance / n < MIN_REL_FLUX_MS) return null

        val specFlux = try {
            SpectralFluxAnalyzer.computeSpectralFlux(
                frames, sampleRate, pcmScale = (SPECTRAL_REFERENCE_RMS / sqrt(meanEnv)).toFloat(),
            )
        } catch (e: Exception) {
            log.warn("Spectral flux computation failed, falling back to energy flux: ${e.message}")
            null
        }
        val (_, eStd) = stats(energyFlux, 0, n)
        val (_, sStd) = if (specFlux != null) stats(specFlux, 0, specFlux.size) else (0f to 0f)
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

        val onsetBias = if (specFlux != null) SPECTRAL_ONSET_BIAS_HOPS else 0.0
        val onsets = measureOnsetSalience(frames, sampleRate, extractOnsets(flux, threshold, onsetBias), minLag)

        val rawCandidates = mutableSetOf<Int>()
        rawCandidates.add(bestLag)
        rawCandidates.add(bestLag * 2)
        rawCandidates.add(round(bestLag / 2.0).toInt())
        val energyBestLag = (minLag..maxLag).maxBy { normalizedCorrelation(eDetrended, eVariance, it) }
        rawCandidates.add(energyBestLag)
        rawCandidates.add(energyBestLag * 2)
        rawCandidates.add(round(energyBestLag / 2.0).toInt())
        // A third harmonic can win the global peak; retain other local tempo hypotheses.
        // Ellis (2007) / librosa evaluate the full lag range before applying the prior.
        for (lag in minLag..maxLag) {
            val left = if (lag > minLag) scores[lag - 1] else -1f
            val right = if (lag < maxLag) scores[lag + 1] else -1f
            if (scores[lag] > left && scores[lag] >= right) rawCandidates.add(lag)
        }

        // Ensure both half-lags and double-lags are present so odd bestLag (e.g. 75 -> 38)
        // has its 2x multiple (76) available for cross-checking without omission.
        val expandedLags = mutableSetOf<Int>()
        for (l in rawCandidates) {
            if (l in minLag..maxLag) expandedLags.add(l)
            if (l * 2 in minLag..maxLag) expandedLags.add(l * 2)
        }
        val candidateLags = expandedLags.filter { it in minLag..maxLag }.toSortedSet()
        if (candidateLags.isEmpty()) return null

        val candidates = candidateLags
            .map { it to normalizedCorrelation(detrended, variance, it).toFloat() }
            .fold(mutableListOf<Pair<Int, Float>>()) { acc, candidate ->
                val last = acc.lastOrNull()
                if (last != null && candidate.first - last.first <= 1) {
                    if (candidate.second > last.second) acc[acc.lastIndex] = candidate
                } else {
                    acc.add(candidate)
                }
                acc
            }
            .map { (lag, _) -> lag to sampleConfidence(detrended, variance, lag) }

        // Threshold prominence describes attack timing, not metrical salience. Include
        // event duration to distinguish short subdivisions from sustained beat accents.
        // A one-hop Gaussian retains fractional onset positions in the autocorrelation.
        val accents = FloatArray(n)
        for (onset in onsets) {
            for (i in maxOf(0, onset.hop.toInt() - 3)..minOf(n - 1, onset.hop.toInt() + 3)) {
                val distance = i - onset.hop
                accents[i] += (onset.salience * exp(-0.5 * distance * distance)).toFloat()
            }
        }
        val (accentSignal, accentVariance) = detrend(accents)
        // Fit each hypothesis before comparing coverage: alternating attack offsets can
        // bias a single-interval median and otherwise smear the correct grid over time.
        val candidateGrids = candidates.associate { (lag, _) ->
            val refined = refineLag(detrended, variance, lag, minLag, maxLag, n)
            val supported = supportedOnsetPeriod(onsets, lag, refined) ?: refined
            lag to optimizePhaseAndPeriod(onsets, supported)
        }
        val accentConfs = candidates.associate { (lag, conf) ->
            lag to if (accentVariance > 0.0) sampleConfidence(accentSignal, accentVariance, lag) else conf
        }
        val scoredLag = candidates.maxByOrNull { (lag, conf) ->
            val accentConf = accentConfs.getValue(lag)
            val (period, phase, _) = candidateGrids.getValue(lag)
            // A half-tempo grid must account for the accents it leaves between its beats.
            val coverage = accentCoverage(onsets, period, phase)
            val score = accentConf * coverage * tempoPrior(60_000.0 / (lag * HOP_MS))
            log.debug("BPM candidate: lag={}, flux confidence={}, accent confidence={}, coverage={}, score={}", lag, conf, accentConf, coverage, score)
            score
        }?.first ?: bestLag
        val chosenLag = if (scoredLag in candidateGrids) {
            resolveSubdivision(scoredLag, onsets, candidateGrids, accentConfs)
        } else {
            scoredLag
        }

        val energyConf = sampleConfidence(eDetrended, eVariance, chosenLag)
        val specConf = sampleConfidence(detrended, variance, chosenLag)
        val rawConf = maxOf(energyConf, specConf)
        val chosenConf = if (onsets.size < 4) 0f else rawConf

        val (refinedPeriod, phaseHops, _) = candidateGrids.getValue(chosenLag)
        val bpm = (60_000.0 / (refinedPeriod * HOP_MS)).toFloat()

        // Metric structure analysis (downbeat & 3/4 check)
        val periodHops = refinedPeriod
        val lag3 = round(3.0 * periodHops).toInt()
        val lag4 = round(4.0 * periodHops).toInt()
        val lag6 = round(6.0 * periodHops).toInt()
        val lag8 = round(8.0 * periodHops).toInt()

        val r3 = if (lag3 in 1 until n && accentVariance > 0.0) normalizedCorrelation(accentSignal, accentVariance, lag3) else 0.0
        val r6 = if (lag6 in 1 until n && accentVariance > 0.0) normalizedCorrelation(accentSignal, accentVariance, lag6) else 0.0
        val r4 = if (lag4 in 1 until n && accentVariance > 0.0) normalizedCorrelation(accentSignal, accentVariance, lag4) else 0.0
        val r8 = if (lag8 in 1 until n && accentVariance > 0.0) normalizedCorrelation(accentSignal, accentVariance, lag8) else 0.0

        val tripleScore = maxOf(0.0, r3) + maxOf(0.0, r6)
        val quadScore = maxOf(0.0, r4) + maxOf(0.0, r8)
        val isNon44 = (tripleScore > 1.25 * quadScore && r3 > 0.35)

        var bestDownbeatHop = phaseHops
        var downbeatConf = 0.0f

        if (!isNon44 && onsets.isNotEmpty()) {
            val barHops = 4.0 * periodHops
            val phaseScores = DoubleArray(4)
            val phaseCounts = IntArray(4)
            val tolHops = maxOf(1.5, periodHops * 0.25)

            for (onset in onsets) {
                val rawOffset = (onset.hop - phaseHops) % barHops
                val normOffset = (rawOffset + barHops) % barHops
                for (p in 0 until 4) {
                    val target = p * periodHops
                    val dist = abs(normOffset - target)
                    val circularDist = minOf(dist, barHops - dist)
                    if (circularDist <= tolHops) {
                        val weight = 1.0 - (circularDist / tolHops)
                        phaseScores[p] += onset.salience * weight
                        phaseCounts[p]++
                    }
                }
            }

            for (p in 0 until 4) {
                if (phaseCounts[p] > 0) {
                    phaseScores[p] /= phaseCounts[p].toDouble()
                }
            }

            val bestPhase = (0 until 4).maxByOrNull { phaseScores[it] } ?: 0
            val bestScore = phaseScores[bestPhase]
            val otherMean = (phaseScores.sum() - bestScore) / 3.0
            if (bestScore > 1e-4 && otherMean > 1e-4) {
                val contrast = ((bestScore - otherMean) / otherMean).coerceIn(0.0, 1.0)
                downbeatConf = (chosenConf * contrast).toFloat().coerceIn(0f, 1f)
            }
            bestDownbeatHop = phaseHops + bestPhase * periodHops
        }

        return Detection(
            bpm = bpm,
            confidence = chosenConf,
            firstBeatIdx = firstBeatIdx,
            phaseHops = phaseHops,
            downbeatHopOffset = bestDownbeatHop,
            downbeatConfidence = downbeatConf,
            isNon44Meter = isNon44,
        )
    }
    private fun supportedOnsetPeriod(
        onsets: List<Onset>,
        lag: Int,
        autoRefinedLag: Double,
    ): Double? {
        if (onsets.size < 4) return null
        // Center the interval window on the sub-hop period. Centering it on the
        // integer lag truncates a jittered interval distribution on one side and
        // biases the median toward that integer (31.58 hops fitted as 31.16).
        val center = autoRefinedLag
        val tol = maxOf(1.5, lag * 0.05)

        val matchedIntervals = mutableListOf<Double>()
        val matchedWeights = mutableListOf<Double>()

        for (i in onsets.indices) {
            val o1 = onsets[i]
            for (j in (i + 1) until onsets.size) {
                val o2 = onsets[j]
                val diff = o2.hop - o1.hop
                if (diff > center + tol) break
                if (abs(diff - center) <= tol) {
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
        return period
    }

    /**
     * Coverage always favors the faster of two octave-related grids: a grid at
     * half the period contains every point of the slower grid. When the events
     * between the slower grid's beats are clearly weaker than those on them,
     * they are subdivisions (eighth-note hats or bass), not beats, and the
     * slower grid is the beat level. Their attack-prominence ratio is weighed
     * against a narrower pairwise tempo prior. Only fast readings of at least
     * [SUBDIVISION_MIN_FAST_BPM] are checked: there, 100 BPM with eighth notes
     * leaves 200, while 125 BPM with loud/soft kicks never collapses to 62.5.
     */
    private fun resolveSubdivision(
        fastLag: Int,
        onsets: List<Onset>,
        grids: Map<Int, Triple<Double, Double, Float>>,
        accentConfs: Map<Int, Float>,
    ): Int {
        val (fastPeriod, fastPhase, _) = grids.getValue(fastLag)
        val fastBpm = 60_000.0 / (fastPeriod * HOP_MS)
        // Below this, the halved reading (< ~78 BPM) is the less plausible beat level and
        // loud/soft alternation of one sound looks just like beats with subdivisions.
        if (fastBpm < SUBDIVISION_MIN_FAST_BPM) return fastLag
        val fastAccent = accentConfs.getValue(fastLag)
        val slow = grids.entries
            .filter { (lag, grid) -> lag != fastLag && abs(grid.first / fastPeriod - 2.0) <= 2.0 * SUBDIVISION_PERIOD_TOLERANCE }
            .maxByOrNull { (lag, _) -> accentConfs.getValue(lag) }
            ?: return fastLag
        val slowLag = slow.key
        // The slower grid must itself be periodic; otherwise it is no beat candidate.
        if (accentConfs.getValue(slowLag) < SUBDIVISION_MIN_ACCENT_RATIO * fastAccent) return fastLag
        // Measure on the fast grid doubled, at both of its alternating phases. A
        // separately fitted slow grid can lock half a beat off; the weaker of the
        // two alternations decides, so a kick/snare backbeat never reads as subdivision.
        val slowPeriod = 2.0 * fastPeriod
        val (ratio, offShare) = listOf(fastPhase, fastPhase + fastPeriod)
            .mapNotNull { halfBeatContrast(onsets, slowPeriod, it) }
            .minByOrNull { it.first }
            ?: return fastLag
        if (offShare < SUBDIVISION_MIN_OFF_SHARE) return fastLag
        val slowBpm = fastBpm / 2.0
        val logPriorRatio = logPairPrior(slowBpm) - logPairPrior(fastBpm)
        val evidence = SUBDIVISION_CONTRAST_GAIN * (SUBDIVISION_CONTRAST_CENTER - ratio) + logPriorRatio
        log.debug(
            "BPM subdivision check: fast={} slow={} offbeat/onbeat prominence={} offShare={} evidence={}",
            fastBpm, slowBpm, ratio, offShare, evidence,
        )
        return if (evidence > 0.0) slowLag else fastLag
    }

    /**
     * Mean attack prominence of onsets at half-beat positions relative to onsets on
     * the beats of the (period, phase) grid, and the half-beat share of those onsets.
     */
    private fun halfBeatContrast(onsets: List<Onset>, period: Double, phase: Double): Pair<Double, Double>? {
        val tolerance = maxOf(1.5, period * 0.10)
        var onSum = 0.0
        var onCount = 0.0
        var offSum = 0.0
        var offCount = 0.0
        for (onset in onsets) {
            val offset = ((onset.hop - phase) % period + period) % period
            val beatDistance = minOf(offset, period - offset)
            val halfDistance = abs(offset - period / 2.0)
            if (beatDistance < tolerance) {
                val kernel = cos(PI * beatDistance / (2.0 * tolerance)).let { it * it }
                onSum += kernel * onset.weight
                onCount += kernel
            } else if (halfDistance < tolerance) {
                val kernel = cos(PI * halfDistance / (2.0 * tolerance)).let { it * it }
                offSum += kernel * onset.weight
                offCount += kernel
            }
        }
        if (onCount <= 0.0 || onSum <= 0.0 || offCount <= 0.0) return null
        return (offSum / offCount) / (onSum / onCount) to offCount / (onCount + offCount)
    }

    private fun logPairPrior(bpm: Double): Double {
        val oct = ln(bpm / PRIOR_CENTER_BPM) / ln(2.0)
        return -0.5 * (oct / SUBDIVISION_PRIOR_SIGMA_OCT) * (oct / SUBDIVISION_PRIOR_SIGMA_OCT)
    }

    private fun accentCoverage(onsets: List<Onset>, period: Double, phase: Double): Double {
        val total = onsets.sumOf { it.salience }
        if (total <= 0.0) return 0.0
        val tolerance = maxOf(1.5, period * 0.10)
        var covered = 0.0
        for (onset in onsets) {
            val offset = ((onset.hop - phase) % period + period) % period
            val distance = minOf(offset, period - offset)
            if (distance < tolerance) {
                val kernel = cos(PI * distance / (2.0 * tolerance))
                covered += onset.salience * kernel * kernel
            }
        }
        return covered / total
    }

    private fun measureOnsetSalience(
        frames: List<Array<FloatArray>>,
        sampleRate: Int,
        onsets: List<Onset>,
        shortestPeriodHops: Int,
    ): List<Onset> {
        val hopSamples = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)
        // Disjoint windows end at the next attack or the shortest allowed beat.
        // Traverse planar PCM once; channel energies avoid anti-phase cancellation.
        val starts = onsets.map { ((it.hop - ACCENT_PREROLL_HOPS) * hopSamples).toLong().coerceAtLeast(0L) }
        var frameIndex = 0
        var frameStart = 0L
        return onsets.mapIndexed { index, onset ->
            val start = starts[index]
            val end = minOf(start + shortestPeriodHops.toLong() * hopSamples, starts.getOrNull(index + 1) ?: Long.MAX_VALUE)
            // A short RMS envelope measures duration without making noisy snare peaks
            // appear shorter than a sinusoidal kick of the same envelope.
            val blockSize = maxOf(1, hopSamples / 4)
            var blockEnergy = 0.0
            var blockSamples = 0
            var area = 0.0
            var peak = 0.0
            fun finishBlock() {
                if (blockSamples == 0) return
                val rms = sqrt(blockEnergy / blockSamples)
                area += rms * blockSamples
                peak = maxOf(peak, rms)
                blockEnergy = 0.0
                blockSamples = 0
            }
            while (frameIndex < frames.size && frameStart < end) {
                val frame = frames[frameIndex]
                val frameEnd = frameStart + frame[0].size
                val lo = (start - frameStart).coerceIn(0L, frame[0].size.toLong()).toInt()
                val hi = (end - frameStart).coerceIn(0L, frame[0].size.toLong()).toInt()
                for (i in lo until hi) {
                    var energy = 0.0
                    for (channel in frame) energy += channel[i].toDouble() * channel[i]
                    blockEnergy += energy / frame.size
                    blockSamples++
                    if (blockSamples == blockSize) finishBlock()
                }
                if (frameEnd >= end) break
                frameIndex++
                frameStart = frameEnd
            }
            finishBlock()
            // Equivalent rectangular duration (area / peak) is invariant to playback gain.
            val durationHops = if (peak > 0.0) area / (peak * hopSamples) else 0.0
            // Saturate threshold prominence: suppress marginal noise without allowing
            // a sharper attack to outweigh all of the longer, quieter beat accents.
            onset.copy(salience = durationHops * onset.weight / (1.0 + onset.weight))
        }
    }

    private fun extractOnsets(flux: FloatArray, threshold: FloatArray, bias: Double): List<Onset> {
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
                onsets.add(Onset(sumPos / sumWeight + bias, sumWeight))
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
        val fit = refinePeriodAndPhaseWls(onsets, initialPeriod, bestPhi)
            ?: return Triple(initialPeriod, bestPhi, alignmentRatio)
        val snapped = snapToIntegerBpm(fit)
        if (snapped != null) {
            log.debug("BPM snapped: {} -> {}", 60_000.0 / (fit.period * HOP_MS), 60_000.0 / (snapped.first * HOP_MS))
            return Triple(snapped.first, snapped.second, alignmentRatio)
        }
        return Triple(fit.period, fit.phase, alignmentRatio)
    }

    private data class AssignedOnset(val beat: Double, val hop: Double, val weight: Double)

    private class GridFit(
        val period: Double,
        val phase: Double,
        val phaseRaw: Double,
        val assigned: List<AssignedOnset>,
    )

    private fun refinePeriodAndPhaseWls(
        onsets: List<Onset>,
        initialPeriod: Double,
        initialPhase: Double,
    ): GridFit? {
        if (onsets.size < 4 || initialPeriod <= 0.0) return null

        var currPeriod = initialPeriod
        var currPhase = initialPhase
        val toleranceRatios = doubleArrayOf(0.22, 0.12, 0.08)
        var best: GridFit? = null

        for (tolRatio in toleranceRatios) {
            val tol = maxOf(1.2, currPeriod * tolRatio)
            var sumW = 0.0
            var sumK = 0.0
            var sumT = 0.0
            val valid = mutableListOf<AssignedOnset>()
            val beatIndices = mutableSetOf<Long>()

            for (onset in onsets) {
                val k = round((onset.hop - currPhase) / currPeriod)
                val expected = currPhase + k * currPeriod
                if (abs(onset.hop - expected) <= tol) {
                    valid.add(AssignedOnset(k, onset.hop, onset.weight))
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
            best = GridFit(currPeriod, currPhase, refinedPhase, valid)
        }

        return best
    }

    private fun weightedRms(assigned: List<AssignedOnset>, period: Double, phaseRaw: Double): Double {
        var sumWeight = 0.0
        var sumSquaredResidual = 0.0
        for ((beat, hop, weight) in assigned) {
            val residual = hop - (phaseRaw + beat * period)
            sumSquaredResidual += weight * residual * residual
            sumWeight += weight
        }
        return sqrt(sumSquaredResidual / sumWeight)
    }

    private fun snapToIntegerBpm(fit: GridFit): Pair<Double, Double>? {
        val bpm = 60_000.0 / (fit.period * HOP_MS)
        val target = round(bpm)
        if (target < 1.0 || abs(bpm - target) > SNAP_MAX_DELTA_BPM) return null
        val assigned = fit.assigned
        if (assigned.size < SNAP_MIN_ONSETS) return null
        if (assigned.maxOf { it.beat } - assigned.minOf { it.beat } < SNAP_MIN_BEAT_SPAN) return null

        val snapPeriod = 60_000.0 / (target * HOP_MS)
        val sumWeight = assigned.sumOf { it.weight }
        val snapPhaseRaw = assigned.sumOf { it.weight * (it.hop - it.beat * snapPeriod) } / sumWeight
        val freeRms = weightedRms(assigned, fit.period, fit.phaseRaw)
        val snapRms = weightedRms(assigned, snapPeriod, snapPhaseRaw)
        log.debug("BPM integer fit: raw={}, target={}, free RMS={}, integer RMS={} hops", bpm, target, freeRms, snapRms)
        if (snapRms > freeRms * SNAP_RMS_FACTOR + SNAP_RMS_SLACK_HOPS) return null
        return snapPeriod to ((snapPhaseRaw % snapPeriod) + snapPeriod) % snapPeriod
    }

    private fun tempoPrior(bpm: Double): Double {
        val oct = ln(bpm / PRIOR_CENTER_BPM) / ln(2.0)
        return exp(-0.5 * (oct / PRIOR_SIGMA_OCT) * (oct / PRIOR_SIGMA_OCT))
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
