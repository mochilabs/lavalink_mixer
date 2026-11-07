# Planner V2 Migration & Dead-Code Removal

Status: planning. Follow-up to `2026-11-06-adaptive-automix-engine.md`, which
built the V2 planner but never wired it end-to-end.

## Current-state diagnosis (verified against code)

The V1 planner (`AdaptiveTransitionPlanner.decide`) still decides the crossfade
*window*, while the V2 planner (`AdaptiveTransitionPlannerV2.plan`) builds a
`TransitionRecipe` that is recorded but never drives the window. Two decision
paths run side by side and disagree.

Working in production today:

- Ordinary equal-power crossfade (`GuildMixer.advance` legacy `cos/sin` branch).
- Echo Out (`MixerFilter` consumes `recipe.echoTrigger`).

Broken in production (code exists, effect is zero):

1. **Metric-gated strategies are unreachable.** `GuildMixer.planCrossfadeLocked`
   feeds `outGrid?.toExtended()`; `toExtended()` → `ExtendedBeatGrid.fromLegacy`
   hard-codes `downbeatConfidence = 0f`, `phraseConfidence = 0f`. Therefore
   `hasDownbeats` / `hasPhrases` in the V2 planner are always false, so
   `PHRASE_MATCHED_BASS_SWAP`, `SHORT_BASS_SWAP`, `DOWNBEAT_CUT` never fire.
   Only `BEATMATCHED_BLEND` or `ECHO_OUT` reachable.
2. **Transition memory has no effect.** `TransitionMemory.record` is called,
   `repetitionWeight` has zero callers. Variation/memory feature is inert.
3. **Recipe timeline is discarded.** `AdaptiveTransitionPlannerV2.toCrossfadePlan`
   has zero callers. `planCrossfadeLocked` returns V1's
   `AdaptiveTransitionPlanner.toCrossfadePlan(decision, …)`. Fade window length
   mismatch → potential 0.707 gain step at handoff.
4. **Filter sweep is never executed.** `TransitionRecipe.filterCutoffAt` has no
   consumer; `DEGRADED_FILTER_SWEEP` is a plain linear fade in practice.
5. **Crossover pre-roll/reset never run.** `CrossoverLR4.preRoll()`/`reset()`
   have no production callers; filter state starts at zero at every transition.
6. **Gain authority is split.** `GuildMixer.advance` evaluates recipe gains;
   `MixerFilter` evaluates bass/echo. Divergent change, two owners of one
   transition.

## Dead-code inventory (grep-verified)

Confirmed dead (zero production callers; tests-only where noted):

- `TransitionMemory.repetitionWeight` — `TransitionMemoryTest` only.
- `AdaptiveTransitionPlannerV2.toCrossfadePlan` — defined, no callers.
- `TransitionRecipe.filterCutoffAt` — defined, no callers.
- `TransitionRecipe.isBassMutedAt` — `TransitionRecipeTest` only.
- `CrossoverLR4.preRoll` — no callers; `reset` — `CrossoverLR4Test` only.
- `TransitionStrategy.SILENCE_TRIM` — never emitted by V2 `plan`.
- `RampCurve.EXPONENTIAL` — never emitted; only read inside `gainAt`.
- `FilterType.LOW_PASS`, `FilterType.BAND_PASS` — never emitted.
- `BeatSyncConfig.startSample`, `handoffSample` — write-only (tests set them).
- `BeatSyncConfig.incomingStartSample` — read only by the dead
  `toCrossfadePlan`.
- `BeatSyncConfig.targetBpm` — never consumed (V2 targetBpm ≠ CrossfadePlan.targetBpm).
- `ExtendedBeatGrid.beatSample`, `barSample`, `downbeatMs` — `BpmGridTest` only.

Broken-by-wiring, not dead (callers exist but the result is inert):

- `BeatGrid.toExtended` → `ExtendedBeatGrid.fromLegacy` hard-codes 0 confidence.
- `AdaptiveTransitionDecision` / V1 `decide` / `selectBoundaries` — still
  invoked, but their only surviving purpose is the window length, which the
  recipe already computes better.

## Silence-trim finding (corrected — two distinct silence measurers)

`SilenceTrim` is **reachable**, via `!hasBeatGrids` (grids null or below
`minConfidence`) when the BPM-window `TransitionFeatures` reports
trailing/leading silence ≥ 100ms. It is not gated out by the
`!crossfadeEnabled` early return.

The trap: two different analyzers measure silence, on different windows, and
can disagree:

- `SilenceAnalyzer` (opt-in `silenceSkipEnabled`, `-60 dB` threshold) → `TrimPoints.trailMs`,
  consumed as `trail` in `poll()` and `contentEnd`.
- `TransitionFeatureAnalyzer` (`SILENCE = 0.001f` RMS, run inside the BPM scan) →
  `TransitionFeatures.trailingSilenceMs/leadingSilenceMs`, consumed by V1 `decide`.

Advance-trigger consequences of deleting `adaptiveTrim`/`SilenceTrim`:

1. **`silenceSkipEnabled = true`** — the generic `trail > 0 && pos >= contentEnd`
   branch (`GuildMixer.kt:1120`) advances at the audible end. Deletion safe.
2. **`silenceSkipEnabled = false` but beatmatch on** (both opt-in, independent) —
   `trail` is 0, so the generic branch does not fire; `adaptiveTrim` is today the
   only early advance at the BPM-window silence point. Deleting it means the
   track plays its trailing silence to the physical `FINISHED` end instead of an
   early cut. Not a stuck track (file end still advances), but a behaviour change
   on a narrow config: silence trimming was never this path's stated feature.

Decision: delete `SilenceTrim`/`adaptiveTrim`/`PlannedTrim`; the audible
silence-skip feature lives entirely in `silenceSkipEnabled` (`contentEnd` +
`seekPastLead` + generic `trail > 0` branch), which is unchanged. The lost
behaviour (beatmatch-on, silence-skip-off, low/no-grid early cut) is a
degenerate overlap of two opt-in features and is acceptable to drop. Recorded
here so the change is deliberate, not accidental.

## Refactor plan (5 phases)

Target seams:

| Module | Interface | Hidden depth |
| --- | --- | --- |
| Analysis | `TrackAnalysisService.analyze() -> ExtendedBeatGrid?` | BPM/downbeat/phrase/metric |
| Planner | `AdaptiveTransitionPlanner.plan(…) -> TransitionRecipe` + `toCrossfadePlan` | waterfall + deadband + octave normalize |
| Executor | `TransitionRecipe` indexed queries | timeline + automations |
| Memory | `TransitionMemory.rank(candidates)` | history penalty + seed |

Core rule: `TransitionRecipe` is the single source of truth for a transition.
`CrossfadePlan` is derived from it. Gain evaluation lives in exactly one place.

### Phase 1 — Make ExtendedBeatGrid flow (restore dead confidence)

- `TrackAnalysisService.analyze` calls `analyzeExtendedGrid` and caches
  `ExtendedBeatGrid` (replace `AnalysisCaches.bpmHead/bpmTail` type, or add
  `bpmHeadExt/bpmTailExt`).
- **Fix the head/tail synthesis fallback**: when one end's grid is null and the
  other is synthesized, run `analyzeExtendedGrid` on the frames already held for
  the missing end instead of constructing a legacy `BeatGrid(...)`. Otherwise
  `toExtended()` re-zeroes half the pairs.
- **Merge phrase confidence**: `analyzeExtendedGrid` returns
  `phraseConfidence = 0f`; copy it from the co-located
  `TransitionFeatures.phraseConfidence` at cache-write time. Without this
  `PHRASE_MATCHED_BASS_SWAP` stays unreachable even after the cache rework.
- Update `currentTempo` / `peekNextBeatGrid` / `transitionAnalysisLog` to read
  extended fields.
- Verify: planner returns `PHRASE_MATCHED_BASS_SWAP` vs `SHORT_BASS_SWAP`
  correctly (not just that `BASS_SWAP` fires).

### Phase 2 — Delete V1, promote V2

- Rename `AdaptiveTransitionPlannerV2` → `AdaptiveTransitionPlanner` (merge
  files), delete `decide` / `AdaptiveTransitionDecision` / `selectBoundaries` /
  V1 `keyClash`.
- `planCrossfadeLocked` uses only `plan()` + `toCrossfadePlan()`;
  `plannedRecipe` becomes the `CrossfadePlan` source.
- Delete `adaptiveTrim` / `PlannedTrim` / the `:1114` poll branch / the
  `SilenceTrim` enum and its tests (per silence-trim finding above). Keep
  `seekPastLead` + `contentEnd` trailing cut — they already do the audible work.
- Verify: `AutomixBoundaryMatrixTest`, `AdaptiveTransitionPlannerV2Test` pass
  under new names; delete/rewrite `AdaptiveTransitionPlannerTest`,
  `AdaptiveCalibration` V1 rows.

### Phase 3 — Unify gain authority

- `MixerFilter` owns all recipe audio evaluation (gain + bass + echo + filter
  cutoff). `GuildMixer.advance` drops the recipe branch and keeps legacy ramps
  (fade-in/duck/mask) + window state.
- Single `activeRecipe` read per chunk.
- Verify: `RecipeExecutorFallbackTest`, `BassSwapIntegrationTest`,
  `EchoOutIntegrationTest` green.

### Phase 4 — Fill the unimplemented executors

- Execute `FilterCutoff` (HPF/LPF sweep) in `MixerFilter` via `filterCutoffAt`.
- Call `CrossoverLR4.preRoll()` ~100ms before transition start + `reset()`.
- Verify: pre-roll case in `CrossoverLR4Test`; sweep integration test.

### Phase 5 — Logging

- INFO one line, same shape as today's crossfade line:
  `transition guild={} from='{}' to='{}' strategy={} reason={} fadeMs={}
  recoveryMs={} bpmOut={} bpmIn={} rateRatio={} downbeatConf={} phraseConf={}
  echoDelayMs={}`
- DEBUG: waterfall branches (deadband decision, octave multiplier `m`,
  `hasDownbeats`/`hasPhrases`, energy/rhythm/key checks), and any
  `TransitionMemory` strategy change.

## Decisions (conservative defaults)

- Gain authority → `MixerFilter`. `GuildMixer` is already overloaded with
  lifecycle/window/feeder arbitration.
- Do not port silence-trim: audible behaviour already lives in `contentEnd` +
  `seekPastLead`; the decision is redundant.
