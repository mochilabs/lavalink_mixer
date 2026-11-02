# High-Precision BPM & Beat Grid Detection Engine Implementation Plan

**Goal:** Upgrade `BpmDetector` to a high-precision, pure Kotlin/JVM beat grid engine with normalized autocorrelation, global phase optimization, and multi-band spectral flux, maintaining zero native/external dependencies and preserving public contracts.

**Architecture:**
- Multi-band Spectral Flux (FFT 2048, Hann Window, Low 30-150Hz / Mid 150-2000Hz / High 2000-10000Hz, per-channel independent FFT + log-magnitude average) fused with broadband Energy Flux.
- Normalized Autocorrelation across lag range 60-200 BPM, eliminating length decay bias.
- Multi-candidate scoring combining normalized lag score, interval distribution (5% relative tolerance), and energy-weighted 8th-note hi-hat cross-checking.
- Global Phase & WLS Joint Optimization fitting beat phase $\phi \in [0, T)$ and refining period $T$ across all onsets in the analysis window, reducing accumulated grid drift.
- Multi-factor Confidence evaluation.
- Pure Kotlin Radix-2 Real FFT (N=2048) with precomputed twiddle and bit-reversal tables.

**Tech Stack:** Kotlin 2.1.20, JVM 17, Gradle 8+, JUnit 5.

---

### Task Breakdown

- **P0: Foundation & Formula Verification**
  - Verify and correct `refineLag`: enforce strict concavity ($denom < 0$), normalize neighbor scores by overlap $(n - lag)$ to eliminate window length bias.
  - Verify tail phase mapping and seek boundary alignment with regression tests.
- **P1: Tempo & Phase Algorithm Upgrade**
  - Replace raw autocorrelation with Normalized Autocorrelation in tempo candidate search.
  - Replace first-onset phase heuristic with Global Phase Optimization ($\arg\max_\phi \sum w_i \cos(2\pi (t_i - \phi)/T)$).
  - Implement Multi-candidate harmonic scoring for octave disambiguation.
- **P2: Multi-band Spectral Analysis**
  - Implement pure Kotlin Radix-2 Real FFT (N=2048) and Hann window.
  - Implement multi-channel 3-band Spectral Flux with dynamic sample-rate scaling (48kHz, 44.1kHz).
  - Calibrate multi-factor confidence.
- **P3: Integration & Verification**
  - Run full test suite: `BpmDetectorTest`, `BpmGridTest`, `BeatMatch*Test`, `GuildMixer*Test`.
  - Validate performance benchmark (< 100ms per 30s audio analysis on JVM 17).

---

### Implementation status (updated 2026-11-02)

The engine is implemented; real-music confidence and metrical-level calibration
remain incomplete. See [research, implementation decisions and measurements](../bpm-detector-design.md).
The historical phase list below is not a new implementation checklist.

The current front end uses 2048-point channel-independent FFTs and averages log
magnitudes. At 48kHz, Low 30–150Hz is bins 1..6, Mid 150–2000Hz is bins 7..85,
and High 2–10kHz is bins 86..427. Energy and spectral analysis levels are normalized.
Tempo selection retains local peaks, scores duration-aware accents and their
coverage by fitted grids, and uses a 120 BPM / one-octave prior;
the proposed 105 BPM center regressed 150 BPM tests and was not adopted.
WLS preserves assignments for residual-gated integer BPM snapping. A measured
1.15-hop correction applies only to spectral onsets. The six strong-hat 70→140
regressions now return 70 BPM, with phase and fast-tempo regression coverage.

- **P0**: `refineLag` strict concavity ($denom < 0$) and normalized autocorrelation overlap checked; Tail phase invariance test passed.
- **P1**: Normalized autocorrelation search implemented; Global Phase Optimization with cosine pulse kernel and WLS joint period refinement implemented; duration-aware accent and grid coverage scoring replaces the former octave promotion rule.
- **P2**: Pure Kotlin Radix-2 Real FFT 2048 (`RealFft.kt`) and multi-channel 3-band Spectral Flux (`SpectralFluxAnalyzer.kt`) with Hann windowing implemented; confidence remains a periodicity score, not calibrated metrical correctness.
- **P3**: Full project test suite passed (`BUILD SUCCESSFUL`), zero external/native dependencies added, public `BeatGrid` and `BeatMatchPolicy` shapes retained. See the current measurement notes for behavior and limits.
