# High-Precision BPM & Beat Grid Detection Engine Implementation Plan

**Goal:** Upgrade `BpmDetector` to a high-precision, pure Kotlin/JVM beat grid engine with normalized autocorrelation, global phase optimization, and multi-band spectral flux, maintaining zero native/external dependencies and preserving public contracts.

**Architecture:**
- Multi-band Spectral Flux (FFT 2048, Hann Window, Low 30-150Hz / Mid 150-2000Hz / High 2000-10000Hz) fused with broadband Energy Flux.
- Normalized Autocorrelation across lag range 60-200 BPM, eliminating length decay bias.
- Multi-candidate scoring combining normalized lag score, octave/harmonic harmonics, and IOI distribution.
- Global Phase Optimization fitting beat phase $\phi \in [0, T)$ across all onsets in the analysis window instead of single first-onset threshold crossing.
- Multi-factor Confidence evaluation.
- Pure Kotlin Radix-2 Real FFT with precomputed twiddle and bit-reversal tables.

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
  - Implement 3-band Spectral Flux (Low, Mid, High) and weighted fusion with Energy Flux.
  - Calibrate multi-factor confidence.
- **P3: Integration & Verification**
  - Run full test suite: `BpmDetectorTest`, `BpmGridTest`, `BeatMatch*Test`, `GuildMixer*Test`.
  - Validate performance benchmark (< 100ms per 30s audio analysis on JVM 17).

---

### Execution Status: COMPLETED
- **P0**: `refineLag` strict concavity ($denom < 0$) and normalized autocorrelation overlap checked; Tail phase invariance test passed.
- **P1**: Normalized autocorrelation detrended search implemented; Global Phase Optimization with cosine pulse kernel implemented and verified against rogue early onsets; multi-candidate octave scoring applied.
- **P2**: Pure Kotlin Radix-2 Real FFT 2048 (`RealFft.kt`) and 3-band Spectral Flux (`SpectralFluxAnalyzer.kt`) with centered Hann window implemented; multi-factor confidence calibrated.
- **P3**: Full project test suite passed (`BUILD SUCCESSFUL in 2m 14s`), zero external/native dependencies added, 100% backward compatibility maintained with `BeatGrid` and `BeatMatchPolicy`.
