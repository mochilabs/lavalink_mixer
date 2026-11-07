# Adaptive Automix Engine Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an AI-DJ-grade Adaptive Automix Engine for `lavalink_mixer` that automatically analyzes outgoing and incoming tracks, selects the optimal transition technique (Phrase-Matched Bass Swap, Echo Out, Filter Sweep, Downbeat Cut, Beatmatched Blend) via an uncertainty-margin multi-level decision tree, and executes sample-accurate DSP automation (LR4 dual crossover, circular delay buffer) without audio clicks or GC allocations.

**Architecture:**
- **Analysis Layer:** `ExtendedBeatGrid` (`periodSamples: Double`, sample coordinates in `Long`, explicit `bpmTail` & `bpmHead`), metric autocorrelation check ($3\tau$ vs $4\tau$ for non-4/4 penalty), 3-way confidence separation (`bpmConfidence`, `downbeatConfidence`, `phraseConfidence`).
- **Planner Layer:** `AdaptiveTransitionPlannerV2` with symmetric $\log_2$ tempo comparison, dynamic uncertainty deadband proportional to `(1 - minBpmConf)`, and confidence-gated waterfall (Level 0 Grid-Based -> Level 1 Degraded -> Level 2 Absolute Fallback).
- **Audio DSP Layer:** Declarative `TransitionRecipe` with sample-level automation events, executed inside `MixerFilter` using pure Kotlin 4th-order Linkwitz-Riley dual crossovers (16 biquads, identical relative delay), 15ms cosine smoothing ramps, and a 96,000-sample circular delay buffer (BPM 40 clamp) with linear termination ramp.
- **Verification Harness:** `OfflineMixRenderer` rendering local WAV pairs + recipe to output WAVs for automated amplitude/click checks and audible verification.

**Tech Stack:** Kotlin 2.1.20, JVM 17, Lavaplayer 2.2.6, JUnit 5, Gradle.

---

### Phase 0: Offline Test Harness

- [x] **Task 0: OfflineMixRenderer Test Harness**
  - **Files:**
    - Create: `src/test/kotlin/io/github/mochilabs/mixer/OfflineMixRenderer.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/OfflineMixRendererTest.kt`
  - **Step 1: Write the failing test**
    - Create `OfflineMixRendererTest` generating two synthetic WAVs (WAV A: 120 BPM clicks, WAV B: 120 BPM clicks) and invoking `OfflineMixRenderer.render(fileA, fileB, recipe, outputFile)`.
    - Verify `outputFile` exists, duration is valid, and audio contains mixed frames.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.OfflineMixRendererTest`
    - Expected: Compilation failure (unresolved reference `OfflineMixRenderer`).
  - **Step 3: Implement OfflineMixRenderer**
    - Adapt the headless `DefaultAudioPlayerManager` + `GuildMixer` + `MixerFilter` pattern from `BeatMatchOutputTest.kt`.
    - Provide a synchronous, headless render loop reading frames from player output into an IEEE Float / 16-bit PCM WAV writer.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.OfflineMixRendererTest`
    - Expected: PASS.
  - **Step 5: Commit**
    - `git add src/test/kotlin/io/github/mochilabs/mixer/OfflineMixRenderer*.kt`
    - `git commit -m "test: add OfflineMixRenderer test harness for track mixing verification"`

---

### Phase 1: Analysis Extension

- [x] **Task 1: ExtendedBeatGrid & Sample Precision**
  - **Files:**
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/analysis/BeatGrid.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/BpmGridTest.kt`
  - **Step 1: Write the failing test**
    - Assert `ExtendedBeatGrid` exposes `periodSamples: Double`, `phaseSample: Long`, `downbeatSample: Long`, `bpmConfidence: Float`, `downbeatConfidence: Float`, `phraseConfidence: Float`.
    - Test that `beatSample(k)` accurately returns `round(phaseSample + k * periodSamples).toLong()` without cumulative integer drift.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.BpmGridTest`
  - **Step 3: Implement ExtendedBeatGrid**
    - Add `ExtendedBeatGrid` data class preserving backwards-compatibility aliases with existing `BeatGrid`.
    - Ensure 48,000Hz sample rate conversion helper methods.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.BpmGridTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/analysis/BeatGrid.kt src/test/kotlin/io/github/mochilabs/mixer/BpmGridTest.kt`
    - `git commit -m "feat: add ExtendedBeatGrid with sample-accurate coordinates and confidence triples"`

- [x] **Task 2: Downbeat Detection & Non-4/4 Metric Check**
  - **Files:**
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/analysis/BpmDetector.kt`
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/analysis/SpectralFlux.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/DownbeatDetectorTest.kt`
  - **Step 1: Write the failing test**
    - Test standard 4/4 drum synthetic signals (kick on 1 and 3, snare on 2 and 4): verify `downbeatSample` matches the first kick and `downbeatConfidence >= 0.7`.
    - Test 3/4 waltz pattern (strong accent every 3 beats): verify `isNon44Meter == true` and `downbeatConfidence == 0.0f`.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.DownbeatDetectorTest`
  - **Step 3: Implement downbeat detection & metric verification**
    - In `BpmDetector.kt`, correlate low-frequency spectral flux onsets at measure lags: compare cumulative $R(3\tau) + R(6\tau) + R(12\tau)$ against $R(4\tau) + R(8\tau) + R(16\tau)$.
    - Fit the 4-phase offset candidate $\arg\max_{p \in \{0,1,2,3\}} \sum_{k} \text{flux}(p + 4k)$ to locate downbeats.
    - Set `downbeatConfidence = 0.0f` if non-4/4 metric dominates.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.DownbeatDetectorTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/analysis/BpmDetector.kt src/test/kotlin/io/github/mochilabs/mixer/DownbeatDetectorTest.kt`
    - `git commit -m "feat: implement downbeat detection and 3/4 metric protection"`

- [x] **Task 3: Phrase Detection & Tail Re-anchoring**
  - **Files:**
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/analysis/TransitionFeatures.kt`
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/analysis/TrackAnalysisService.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/PhraseDetectionTest.kt`
  - **Step 1: Write the failing test**
    - Test synthetic 16-bar track with energy build-up/drop every 8 bars: assert `phraseConfidence >= 0.6` and phrase boundary identified at bar 8/16.
    - Verify `TrackAnalysisService` populates `bpmTail` directly from `tailFrames` without relying on head phase extrapolation.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.PhraseDetectionTest`
  - **Step 3: Implement phrase detection**
    - Analyze energy envelope variance across 8-bar and 16-bar blocks aligned with detected downbeats.
    - Compute `phraseConfidence` from boundary energy gradient and spectral flux concentration.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.PhraseDetectionTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/analysis/TransitionFeatures.kt src/test/kotlin/io/github/mochilabs/mixer/PhraseDetectionTest.kt`
    - `git commit -m "feat: implement phrase boundary detection and tail grid re-anchoring"`

---

### Phase 2: Recipe Foundation & Safe Fallback

- [x] **Task 4: TransitionRecipe Data Model & Serialization**
  - **Files:**
    - Create: `src/main/kotlin/io/github/mochilabs/mixer/analysis/TransitionRecipe.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/TransitionRecipeTest.kt`
  - **Step 1: Write the failing test**
    - Test `TransitionRecipe` creation with `ParamAutomation` list (VolumeRamp, FilterCutoff, BassCut, EchoTrigger).
    - Test evaluation of automation values at progress `0.0`, `0.5`, `1.0`.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.TransitionRecipeTest`
  - **Step 3: Implement TransitionRecipe**
    - Define sealed interface `ParamAutomation` and immutable `TransitionRecipe`.
    - Provide query helpers for instantaneous filter cutoff, bass enable flag, and gain multipliers at sample index $n$.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.TransitionRecipeTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/analysis/TransitionRecipe.kt src/test/kotlin/io/github/mochilabs/mixer/TransitionRecipeTest.kt`
    - `git commit -m "feat: define TransitionRecipe schema and automation curves"`

- [x] **Task 5: RecipeExecutor & Level 2 Fallback Bridge in MixerFilter**
  - **Files:**
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/audio/MixerFilter.kt`
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/GuildMixer.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/RecipeExecutorFallbackTest.kt`
  - **Step 1: Write the failing test**
    - Configure a Level 2 fallback recipe (5-second equal power).
    - Verify `MixerFilter` executes the recipe identically to legacy crossfade without regressions.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.RecipeExecutorFallbackTest`
  - **Step 3: Implement RecipeExecutor bridge**
    - In `GuildMixer`, add active `TransitionRecipe` slot.
    - In `MixerFilter`, read automation parameters on chunk boundary, falling back to legacy `GainRamp` if no recipe is active.
  - **Step 4: Run test to verify it passes**
    - Run both new test and existing regression suite:
      `./gradlew test --tests io.github.mochilabs.mixer.RecipeExecutorFallbackTest`
      `./gradlew test --tests io.github.mochilabs.mixer.AdaptiveTransitionRaceTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/audio/MixerFilter.kt src/test/kotlin/io/github/mochilabs/mixer/RecipeExecutorFallbackTest.kt`
    - `git commit -m "feat: bridge TransitionRecipe execution into MixerFilter with Level 2 fallback"`

---

### Phase 3: DSP Core (Biquad & LR4 Dual Crossover)

- [x] **Task 6: Pure Kotlin BiquadFilter & CrossoverLR4 Engine**
  - **Files:**
    - Create: `src/main/kotlin/io/github/mochilabs/mixer/audio/BiquadFilter.kt`
    - Create: `src/main/kotlin/io/github/mochilabs/mixer/audio/CrossoverLR4.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/CrossoverLR4Test.kt`
  - **Step 1: Write the failing test**
    - Test LR4 200Hz crossover on synthetic sine sweep (20Hz to 20kHz): assert summed magnitude response $|H_{LP} + H_{HP}| = 0.0\text{ dB} \pm 0.05\text{ dB}$ across all frequencies.
    - Test group delay symmetry: verify Left and Right channels exhibit identical phase response without DC offset.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.CrossoverLR4Test`
  - **Step 3: Implement BiquadFilter & CrossoverLR4**
    - Implement Direct Form II Transposed biquad filter with zero allocation in `process(samples, offset, length)`.
    - Implement 4th-order Linkwitz-Riley crossover by cascading two 2nd-order Butterworth biquads ($Q = 1/\sqrt{2}$) for LP and HP respectively (16 biquads total for 2 stereo tracks).
    - Provide `preRoll()` to initialize state variables without transient clicks.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.CrossoverLR4Test`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/audio/BiquadFilter.kt src/main/kotlin/io/github/mochilabs/mixer/audio/CrossoverLR4.kt src/test/kotlin/io/github/mochilabs/mixer/CrossoverLR4Test.kt`
    - `git commit -m "feat: implement zero-allocation BiquadFilter and LR4 dual crossover"`

- [x] **Task 7: Bass Swap & Filter Sweep in MixerFilter**
  - **Files:**
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/audio/MixerFilter.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/BassSwapIntegrationTest.kt`
  - **Step 1: Write the failing test**
    - Test Bass Swap transition: verify incoming track's low-end is suppressed until cut point, then instantaneously swapped with outgoing low-end over a 15ms cosine ramp.
    - Verify max peak amplitude does not exceed 1.0 (no low-end doubling mud).
    - Verify zero clicks/discontinuities around the swap boundary ($\Delta x[n] < 0.05$).
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.BassSwapIntegrationTest`
  - **Step 3: Implement Bass Swap and HPF/LPF sweep execution**
    - Route both `main` and `sub` PCM through `CrossoverLR4`.
    - Apply cosine smoothing ramp (720 samples @ 48kHz = 15ms) across the bass swap event.
    - Apply dynamic biquad coefficient updates for HPF sweep ($20\text{Hz} \to 3000\text{Hz}$).
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.BassSwapIntegrationTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/audio/MixerFilter.kt src/test/kotlin/io/github/mochilabs/mixer/BassSwapIntegrationTest.kt`
    - `git commit -m "feat: execute Bass Swap and Filter Sweep inside MixerFilter"`

---

### Phase 4: Time-Domain DSP (Circular Delay Buffer & Echo Out)

- [x] **Task 8: CircularDelayBuffer Module**
  - **Files:**
    - Create: `src/main/kotlin/io/github/mochilabs/mixer/audio/CircularDelayBuffer.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/CircularDelayBufferTest.kt`
  - **Step 1: Write the failing test**
    - Allocate buffer with 96,000 samples capacity (2.0s @ 48kHz stereo, accommodating BPM 40).
    - Test writing an impulse at $t=0$ and reading at delay $D = 24,000$ samples: assert exact impulse output with feedback decay.
    - Test linear termination ramp: verify output drops to exact 0.0 after ramp duration.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.CircularDelayBufferTest`
  - **Step 3: Implement CircularDelayBuffer**
    - Implement pre-allocated `FloatArray(96000 * 2)` circular buffer with read/write heads and linear interpolation for fractional delay.
    - Implement feedback loop with damping filter and explicit linear fade-out termination envelope (1.5~2.0s).
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.CircularDelayBufferTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/audio/CircularDelayBuffer.kt src/test/kotlin/io/github/mochilabs/mixer/CircularDelayBufferTest.kt`
    - `git commit -m "feat: implement pre-allocated CircularDelayBuffer with termination envelope"`

- [x] **Task 9: Beat-Synced Echo Out Effect Integration**
  - **Files:**
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/audio/MixerFilter.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/EchoOutIntegrationTest.kt`
  - **Step 1: Write the failing test**
    - Trigger Echo Out recipe on outgoing track (128 BPM, 1/2-beat delay = 234ms).
    - Verify outgoing dry audio cuts on the downbeat, wet echo rings out with smooth decay, and reaches $< -60\text{ dB}$ within 2.0s.
    - Verify incoming track starts cleanly at $t=0$ without dry delay bleed.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.EchoOutIntegrationTest`
  - **Step 3: Implement Echo Out in MixerFilter**
    - Feed outgoing track audio into `CircularDelayBuffer` during transition start.
    - Sum wet delay tail with incoming track's audio, clamping feedback at 0.65 and applying 1.8s termination ramp.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.EchoOutIntegrationTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/audio/MixerFilter.kt src/test/kotlin/io/github/mochilabs/mixer/EchoOutIntegrationTest.kt`
    - `git commit -m "feat: integrate beat-synced Echo Out into MixerFilter"`

---

### Phase 5: Planner Integration & Decision Engine

- [x] **Task 10: AdaptiveTransitionPlannerV2 Decision Logic**
  - **Files:**
    - Create: `src/main/kotlin/io/github/mochilabs/mixer/analysis/AdaptiveTransitionPlannerV2.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/AdaptiveTransitionPlannerV2Test.kt`
  - **Step 1: Write the failing test**
    - Test symmetric $\log_2$ tempo distance with $\{1.0, 0.5, 2.0\}$ multipliers.
    - Test dynamic uncertainty deadband: verify deadband expands when `minBpmConf` drops, and triggers fallback to Echo Out when RMS ratio $> 3.16$.
    - Test confidence waterfall: `downbeatConf < 0.5` degrades Bass Swap to Beatmatched Blend.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.AdaptiveTransitionPlannerV2Test`
  - **Step 3: Implement AdaptiveTransitionPlannerV2**
    - Implement the full decision tree, deadband math, octave normalization, and recipe generator.
    - Connect Level 0 (Phrase Match / Bass Swap / Beatmatched Blend / Downbeat Cut / Echo Out), Level 1 (Degraded Filter Sweep / Trim), and Level 2 (Absolute Fallback).
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.AdaptiveTransitionPlannerV2Test`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/analysis/AdaptiveTransitionPlannerV2.kt src/test/kotlin/io/github/mochilabs/mixer/AdaptiveTransitionPlannerV2Test.kt`
    - `git commit -m "feat: implement AdaptiveTransitionPlannerV2 with dynamic deadband and recipe generation"`

- [x] **Task 11: 10-Scenario Boundary Matrix CI Test Suite**
  - **Files:**
    - Create: `src/test/kotlin/io/github/mochilabs/mixer/AutomixBoundaryMatrixTest.kt`
  - **Step 1: Write the failing test**
    - Implement the 10 scenarios from the specification:
      1. Standard EDM (128 -> 128) -> PhraseMatchedBassSwap 16-beat
      2. Weak phrase confidence -> PhraseMatchedBassSwap 8-beat
      3. Weak downbeat confidence -> BeatmatchedBlend 4-beat
      4. Double-time (174 DnB -> 87 Trap) -> Bass Swap with $m=0.5$
      5. Deadband stable -> Bass Swap
      6. Deadband energy mismatch -> Echo Out
      7. Extreme tempo gap (130 -> 95) -> Downbeat Cut
      8. Missing incoming metadata -> Echo Out (outgoing tempo only)
      9. 3/4 waltz incoming -> BeatmatchedBlend / Level 1
      10. All metadata missing -> Absolute Fallback 5s Equal-Power
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.AutomixBoundaryMatrixTest`
  - **Step 3: Connect planner and wire any edge-case thresholds**
    - Ensure all 10 scenarios produce exact matching `TransitionRecipe` outputs.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.AutomixBoundaryMatrixTest`
  - **Step 5: Commit**
    - `git add src/test/kotlin/io/github/mochilabs/mixer/AutomixBoundaryMatrixTest.kt`
    - `git commit -m "test: add 10-scenario boundary matrix CI test suite"`

---

### Phase 6: Intelligence & History

- [x] **Task 12: TransitionMemory & Weighted Variation**
  - **Files:**
    - Create: `src/main/kotlin/io/github/mochilabs/mixer/analysis/TransitionMemory.kt`
    - Modify: `src/main/kotlin/io/github/mochilabs/mixer/GuildMixer.kt`
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/TransitionMemoryTest.kt`
  - **Step 1: Write the failing test**
    - Test history tracking: record 3 consecutive `BASS_SWAP` transitions for a guild.
    - When 4th transition occurs between identical tracks, assert planner applies history penalty and selects viable alternative (e.g. `FILTER_SWEEP` or `DOWNBEAT_CUT`).
    - Test deterministic pseudo-random seed based on track pair ID hash.
  - **Step 2: Run test to verify it fails**
    - `./gradlew test --tests io.github.mochilabs.mixer.TransitionMemoryTest`
  - **Step 3: Implement TransitionMemory**
    - Keep a bounded circular history of the last 5 transition techniques per guild.
    - Apply multiplicative penalty (0.3x) to candidate techniques used in the last 2 transitions.
  - **Step 4: Run test to verify it passes**
    - `./gradlew test --tests io.github.mochilabs.mixer.TransitionMemoryTest`
  - **Step 5: Commit**
    - `git add src/main/kotlin/io/github/mochilabs/mixer/analysis/TransitionMemory.kt src/test/kotlin/io/github/mochilabs/mixer/TransitionMemoryTest.kt`
    - `git commit -m "feat: implement TransitionMemory and track-pair seeded variation"`

- [x] **Task 13: End-to-End Audio Integration & Full Regression Run**
  - **Files:**
    - Test: `src/test/kotlin/io/github/mochilabs/mixer/AdaptiveAutomixEndToEndTest.kt`
  - **Step 1: Write E2E integration test using OfflineMixRenderer**
    - Run full pipeline: Track A WAV + Track B WAV -> Analysis -> Planner -> Recipe -> MixerFilter -> Output WAV.
    - Measure output RMS continuity, absence of clicks, and exact beat alignment.
  - **Step 2: Execute full test suite and build**
    - `./gradlew build`
    - Verify all existing tests (`BeatMatchOutputTest`, `TempoStretcherTest`, `AdaptiveTransitionRaceTest`) pass without failures.
  - **Step 3: Commit and finalize documentation**
    - Update `docs/architecture.md` with new `TransitionRecipe` and DSP crossover diagrams.
    - `git commit -m "docs: document Adaptive Automix Engine architecture and DSP pipeline"`
