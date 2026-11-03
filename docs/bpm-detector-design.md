# BPM detector: evidence and current policy

For a step-by-step explanation in Korean, see
[BPM extraction algorithm](bpm-extraction.ko.md). This document records research,
policy decisions, and previous measurements rather than an input-to-output walkthrough.

Reviewed 2026-11-02. Production remains pure Kotlin/JVM, with no additional
runtime dependency. The references below were read as papers and source code;
their complete implementations were not benchmarked against this plugin.

## Research and implementations

| Reference | Relevant design | Application here |
| --- | --- | --- |
| [Ellis, 2007](https://www.ee.columbia.edu/~dpwe/pubs/Ellis07-beattrack.pdf) and [librosa tempo source](https://librosa.org/doc/0.11.0/_modules/librosa/feature/rhythm.html) | Autocorrelation with a log-tempo prior; Ellis subsequently tracks beat sequences by dynamic programming. librosa evaluates tempo across its lag axis. | Preserve local peaks across the full search range before choosing a tempo. This detector still fits a constant local grid; it does not implement the DP tracker. |
| [Dixon, BeatRoot, 2007](https://webspace.eecs.qmul.ac.uk/s.e.dixon/pub/2007/jnmr07.pdf) and [TarsosDSP Java implementation](https://github.com/JorenSix/TarsosDSP/tree/master/core/src/main/java/be/tarsos/dsp/beatroot) | Clusters inter-onset intervals, retains several tempo/phase hypotheses, then evaluates matching events. The paper explicitly separates metrical ambiguity from timing accuracy. | Distinguish onset timing from metrical salience. Dixon also discusses duration-based salience for symbolic notes; our bounded PCM duration proxy and grid scoring are not a BeatRoot port. |
| [Essentia MultiFeature paper](https://www.music-ir.org/mirex/abstracts/2013/ZDBG1.pdf) and [TempoTapDegara source](https://github.com/MTG/essentia/blob/master/src/algorithms/rhythm/tempotapdegara.cpp) | Independent onset functions feed beat trackers; agreement between their outputs selects a result. Degara uses local period observations and temporal tracking. | Combining spectral bands into one flux is not equivalent to independent tracker agreement. A future algorithm replacement needs this distinction and labeled music evaluation. |
| [Mixxx BeatUtils](https://github.com/mixxxdj/mixxx/blob/main/src/track/beatutils.cpp) | Finds regions compatible with constant tempo and rounds BPM only inside a range supported by beat timing. | Compare free-period and integer-period fits on the same observations, refitting phase. Our WLS/RMS acceptance rule differs from Mixxx's region algorithm. |
| [Beat This! paper](https://arxiv.org/abs/2407.21658) and [official implementation](https://github.com/CPJKU/beat_this) | Trained beat/downbeat estimation with a Python/PyTorch runtime. | Considered during research, excluded from this implementation because the runtime must remain Kotlin/JVM. |

These references support separating onset detection, tempo hypotheses, beat timing,
and grid regularization. They do not establish universal values for this plugin's
flux gate, tempo prior, phase correction, or snap tolerance.

## Implemented choices

- Mean-normalize the mean-square energy envelope. Reject near-digital silence at
  mean energy below `1e-9`, and reject detrended relative flux mean square below
  `0.02`. Log the latter at DEBUG; `0.02` remains provisional.
- Normalize FFT input magnitude to a common window RMS of `0.1` before logarithmic
  compression. Envelope normalization alone left confidence changing by about
  `0.063` between the three tested levels because `log(1+x)` is nonlinear.
- Keep N=2048, Hann, 10ms hops, independent channel FFTs and averaged log magnitudes.
  At 48kHz the 30–150Hz, 150–2000Hz and 2–10kHz bands use bins 1..6, 7..85,
  and 86..427. Existing `1024/N` magnitude scaling remains; no new Hann-sum gain
  policy was introduced.
- Retain local autocorrelation maxima, the strongest energy candidate and octave
  hypotheses. Merge lags within one hop using their actual autocorrelation values
  before applying the existing neighboring-lag confidence sampling. Otherwise
  adjacent candidates can share the same sampled confidence and keep the wrong peak.
- Score candidate grids using duration-aware accent autocorrelation, the fraction
  of accent salience covered by the fitted grid, and the log-tempo prior. The old
  2.15 onset-support promotion and 0.04 faster-tempo preference are removed.
  Keep the prior center at **120 BPM**, width **1 octave**. The proposed center
  105 previously collapsed 150 BPM clicks and drums to 75 BPM; it is not adopted.
- Preserve the final successful WLS assignments and their unwrapped phase.
  Integer snapping requires a difference of at most 0.15 BPM, at least eight
  assigned onsets, and a beat-index span of at least twelve. Refit only phase at
  the integer period and require `integer RMS <= free RMS * 1.2 + 0.1 hops`.
  This is compatibility with observed timing, not proof of a programmed tempo.
- Apply a measured `1.15`-hop onset correction only when spectral flux is used.
  `firstBeatMs` still comes from energy onset detection without this correction.
  Recalibrate after changing FFT size, framing, fusion or onset extraction.
- Pass the analysis PCM sample rate explicitly in all detector calls.
  Lavaplayer's analysis output is 48kHz, including resampled 44.1kHz input.
  Tail scan starts already use trimmed content end; the new integration case
  verifies both normal analysis and explicit tail reanalysis with long silence.

## Root cause of the strong-hi-hat octave errors

The strict 70/90 BPM hat sweep was restored before the fix. All six 70 BPM
cases failed at 140 BPM. The previous onset weight measured threshold prominence,
so a short, sharp hat could dominate a longer beat accent. The fused flux also
lost that distinction: at 70 BPM and hat ratio 0.8, its sampled eighth-note
correlation was about 0.956 versus 0.905 at the quarter-note period. Lowering the
prior center did not fix this while preserving fast tempos.

The detector now measures event duration from a short RMS envelope (four blocks
per 10ms hop). For each onset, it integrates RMS and divides by peak RMS. The
window starts two hops before the onset centroid and ends at the next onset or
the shortest allowed beat period. The frame cursor moves forward across decoder
chunks; channel energies are averaged before RMS, preserving anti-phase stereo.
Duration is gain invariant. Using a short RMS envelope avoids comparing noisy
snare sample peaks directly with sinusoidal kick sample peaks.

Metrical salience is duration times `weight / (1 + weight)`. This soft saturation
retains rejection of marginal noise while bounding the influence of attack
prominence. A one-hop Gaussian places these accents at fractional onset times.
Its autocorrelation supplies the periodicity term. Each candidate is fitted
before scoring; the coverage term is the fraction of salience aligned with that
grid, using the existing cosine timing kernel. A kick-only half-tempo grid loses
coverage of the intervening snares. A duration-only or autocorrelation-only
experiment failed the fast-tempo and quiet-intro checks and was discarded.

The final score is `accent correlation × coverage × tempo prior`. The original
onset weights still drive interval timing and WLS; they no longer serve directly
as metrical salience. There is no special-case BPM band or forced division by two.
These are tested DSP policies, not a trained model or a claim to solve metrical
ambiguity for all music.

## Reproducible checks and limits

Run the deterministic synthetic and local WAV tests with JDK 17:

```bash
./gradlew test --tests 'io.github.mochilabs.mixer.BpmPrecisionTest' \
  --tests 'io.github.mochilabs.mixer.BpmGridTest' \
  --tests 'io.github.mochilabs.mixer.BpmDetectorTest' \
  --tests 'io.github.mochilabs.mixer.BpmIntegrationTest'
```

The tests print measured values into the Gradle test report. Enable DEBUG for
`io.github.mochilabs.mixer.BpmDetector` to inspect relative flux, candidate accent
periodicity/coverage/scores, and free/integer residuals. Fixed random seeds make
these signal-processing checks deterministic; they are not a representative music accuracy benchmark.

| Check | Observed result |
| --- | --- |
| One 128 BPM kick/snare waveform at -6/-18/-30 dBFS peak | 128.0 BPM at every level; confidence range below `1e-7` |
| White noise and 440Hz tone, three levels each | All rejected; relative flux mean squares about `0.001104` and `0.0000956` respectively |
| 70/75/80/85/90 BPM kick/snare patterns | Correct quarter-note tempo in these five synthetic cases |
| 70/90 BPM, six hat/kick amplitude ratios from 0.3 to 0.8 | All twelve return the quarter-note BPM exactly. All phases are 251ms for the 250ms reference. The six formerly failing 70 BPM cases return 70.0. |
| 30s: 128.00 / 120.03 / 120.10 BPM kicks | 128.0 and 120.0 snap; 120.10 stays fractional |
| 127.6 BPM with ±10ms jitter; 5s of 128.08 BPM | Fractional; insufficient beat span prevents snapping in the short case |
| 120 BPM kicks, offsets 250..259ms, at 44.1/48kHz (20 cases) | Signed phase error after correction: -2..+2ms; per-rate means -0.4ms and +0.5ms. Before correction: means -7.1ms and -6.0ms |
| 4kHz click PCM | Spectral bands cannot be formed; energy fallback retains 250ms phase without spectral bias |
| 12s versus 30s, six steady synthetic tempos | Both within 0.15 BPM; 120.10 estimates were about 120.079 and 120.100 respectively |

The hat sweep requires quarter-note accuracy; returning the eighth-note rate
fails. Twelve additional cases use 65/80/110/140/150/174 BPM, hat ratios 0.3/0.8,
a different random seed, a 137ms onset offset and 16s windows. All return the
reference tempo exactly and 138ms phase. Four further 70 BPM cases cover
44.1/48kHz and 12/30s windows; each compares mono against -30dB anti-phase stereo
split into alternating 137/2048-sample chunks. BPM and phase agree exactly,
and confidence differs by less than `1e-5`.

These added cases were used during development, not reserved as an independent
accuracy benchmark. Confidence still measures periodicity and must not be
interpreted as an octave correctness score.

Existing coverage for waltz, swing, gradual tempo drift and a quiet intro before
a loud drop remains. The arbitrary-window test now crops actual globally timed
PCM rather than assigning an arbitrary start timestamp to unrelated local clicks.

The final JDK 17 build (`./gradlew build -Pversion=0.3.7`) passed all 238 tests
in 35 classes with zero failures, errors or skips. The JAR descriptor was checked
for version `0.3.7`.

## Local 12s scan experiment

Two WAVs already present in `build/tmp/test-audio` were each measured once per
scan duration with the final v0.3.7 detector and `bpmCalibration` (four head/tail
observations per duration).
No reference beat timestamps were available. Results are observations, not accuracy
scores, and the scan setting is requested length, not exact captured duration.

| File/window | 30s BPM / confidence | 12s BPM / confidence |
| --- | --- | --- |
| bansanka/head | 103.000 / 0.253 | 68.744 / 0.456 |
| bansanka/tail | 69.724 / 0.181 | 69.491 / 0.266 |
| vampire/head | 164.000 / 0.476 | 164.000 / 0.480 |
| vampire/tail | 164.000 / 0.374 | 81.751 / 0.475 |

Relative flux mean square across these windows ranged from about 0.057 to 0.414,
above the provisional 0.02 gate. This small unlabeled sample cannot establish a
safe general threshold. The selected tempo changes materially with scan duration,
so the default remains **30 seconds**. Reproduce on local audio with:

```bash
./gradlew bpmCalibration -PaudioDir=/path/to/wavs -PscanMs=30000 -PoutputFile=build/bpm-30s.tsv
./gradlew bpmCalibration -PaudioDir=/path/to/wavs -PscanMs=12000 -PoutputFile=build/bpm-12s.tsv
```

See [the calibration workflow](bpm-calibration.md) for labeled calibration and
held-out evaluation. Neither these offline results nor a successful build prove
playback quality through a live Discord voice connection.
