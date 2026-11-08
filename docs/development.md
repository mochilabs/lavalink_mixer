# Develop and verify the plugin

## Build locally

Use JDK 17 to match CI. The Gradle wrapper pins Gradle 8.14.1. Initial dependency
resolution needs access to Maven Central and `maven.lavalink.dev`.

Run commands from the repository root:

```bash
./gradlew build
./gradlew test
./gradlew jar
```

`build` compiles the plugin, runs tests, and packages the JAR. `jar` packages
without running tests. Find artifacts under `build/libs/` and the test report
at `build/reports/tests/test/index.html`.

If Java is missing from `PATH`, point `JAVA_HOME` to an installed JDK and add
its `bin` directory to `PATH`. This checkout has an ignored local JDK that can
be used as follows, when that directory exists:

```bash
JAVA_HOME="$PWD/.jdks/jdk-17.0.21+9" PATH="$PWD/.jdks/jdk-17.0.21+9/bin:$PATH" ./gradlew build
```

Do not assume `.jdks/` exists in another checkout. The build sets a JVM target
but does not configure automatic JDK provisioning.

## Select tests for a change

Run a test class by its fully qualified name:

```bash
./gradlew test --tests 'io.github.mochilabs.mixer.GuildMixerProtocolTest'
./gradlew test --tests 'io.github.mochilabs.mixer.*BeatMatch*'
```

Use these existing tests as starting points:

| Change | Relevant test classes |
| --- | --- |
| Epochs, idempotency, reset, override | `GuildMixerProtocolTest` |
| Stale transitions and handoff | `StaleXfadeAbortTest`, `CrossfadeMixIntegrationTest`, `XfadeVoicePathTest` |
| Filter chain binding and reconnects | `MixerFilterExtensionTest`, `ChainDiagnosticTest`, `FeederArbitrationTest`, `FirstTrackIntegrationTest` |
| PCM and gain behavior | `MixerFilterTest`, `GainRampTest`, `SiphonQueueTest` |
| Transition callback locks and decoder readiness | `PlayerCallbackLockTest`, `OverlapPreparationTest`, `XfadeVoicePathTest` |
| Musical key estimation and REST metadata | `KeyDetectorTest`, `KeyIntegrationTest`, `TransitionFeaturesTest` |
| Transition console diagnostics | `TransitionDiagnosticsTest`, `BeatMatchOutputTest`, `BeatMatchIntegrationTest` |
| Announcements | `AnnounceQueueTest`, `IdleAnnounceTest` |
| Analysis ownership and retries | `AnalysisIsolatedFromLiveSubTest`, `DeferredAnalysisTest` |
| Silence and trim | `SilenceAnalyzerTest`, `TailCutDeciderTest`, `TrimTransitionTest` |
| Loudness | `NormalizationGainTest`, `NormalizationBaselineTest`, `NormalizationIntegrationTest` |
| BPM and beat matching | `RealFftTest`, `BpmDetectorTest`, `BpmPrecisionTest`, `BpmGridTest`, `BpmIntegrationTest`, `BpmOctaveTest`, `DownbeatDetectorTest`, `PhraseDetectionTest`, `BeatMatchIntegrationTest`, `BeatMatchOutputTest`, `TempoStretcherTest`, `TempoEnvelopeTest`, `AdaptiveRecoveryIntegrationTest`, `AdaptiveRuntimeTest`, `AdaptiveTransitionRaceTest`, `TransitionFeaturesTest`, `AdaptiveTransitionPlannerTest`, `AutomixBoundaryMatrixTest`, `TransitionRecipeTest`, `TransitionMemoryTest`, `BassSwapIntegrationTest`, `EchoOutIntegrationTest`, `CrossoverLR4Test` |

Integration tests generate local WAV files and exercise Lavaplayer players.
They do not require a running Discord bot. Some tests exercise native audio
libraries, so distinguish native-loading failures from behavioral failures.
Use unique track identifiers in new analysis tests because caches are shared.
Release players and executors in test cleanup.

For protocol timing and re-requests after an empty arm, also run
`ProtocolPrefetchTest`. For the detector's stages and interpretation of results,
see the [BPM extraction explanation](bpm-extraction.ko.md). The
[detailed bot integration guide](../examples/lavalink-client/README.ko.md) describes
queue reservations, uncertain request outcomes, and reconnect reconciliation.

After code changes, run `./gradlew build` to cover interactions beyond the
selected tests. For documentation-only changes, check referenced symbols,
commands, paths, and links against the repository.

## Verify live integration

For adaptive pair decisions and analysis timing, use the
[local pair evaluation runner](adaptive-calibration.md). It accepts an explicit
WAV-pair manifest and preserves uncalibrated/unknown results in its report.

For offline head/tail BPM measurements, use the
[local calibration runner](bpm-calibration.md). Synthetic smoke checks validate
the runner, not the confidence threshold for real music.

1. Build with `./gradlew jar`.
2. Copy the resulting JAR into a compatible Lavalink server's `plugins/` directory.
3. Restart Lavalink and inspect plugin startup logs.
4. Enable `pluginFilters.mixer` with the guild ID as a string before playback.
5. Use the [bot wiring guide](../examples/lavalink-client/README.md) to exercise
   JIT requests, arming, advancement, and queue synchronization.
6. Check `/mixer/state` for filter activity, analysis state, next-track identity,
   and handoff timing while listening to the transition.

For missing overlap audio, inspect filter binding and downstream forwarding
before changing fade durations. For repeated or skipped tracks, inspect bot
auto-skip behavior, epochs, and `MixerAdvancedEvent` handling.

The TypeScript client example has no repository-level package manifest or
dedicated type-check task. Validate example changes in a consuming bot project
and state when that validation is unavailable.

### Idle-main announcements

A local audio test does not prove playback through Discord. After changing the idle carrier (`SilenceAudioTrack`, `maintainIdleCarrier`), check on a real node with no track playing:

1. `/mixer/announce` in sequential and parallel mode with `channel` `both`, `left`, and `right` is heard on the right sides.
2. `announceStart` follows the request promptly, and the carrier stops about one second after the last `announceEnd` (log lines `idle announcement carrier started/stopped`).
3. The bot client library ignores `TrackStartEvent` and `TrackEndEvent` (reason `stopped`) for identifier `lavalink-mixer:idle-carrier` and does not queue it or log warnings.
4. While the carrier runs, `GET player` reports it as `track` and playerUpdate positions advance. Bots that read these must filter the identifier.
5. A bot `play` request replaces the carrier at once, and an announcement still playing continues over the new track. A request with `noReplace=true` is ignored by Lavalink while the carrier runs.
6. Back-to-back announcements reuse one carrier (one start, one end in the logs).
7. A paused player or a guild without a voice connection stays silent.

## Read transition logs

A started crossfade produces one INFO line. `beatMatched=true` means preparation
committed with beat matching active. `strategy` reports the selected `TransitionStrategy`
(`phrase_matched_bass_swap`, `short_bass_swap`, `beatmatched_blend`, `downbeat_cut`,
`echo_out`, `degraded_filter_sweep`, `absolute_fallback`, or `crossfade`). `targetBpm`,
`rateRatio`, `fadeBeats`, and `echoDelayMs` report its target tempo, source speed ratio,
number of fade beats, and Echo Out delay length. A fallback reports `beatMatched=false`,
`rateRatio=1.0`, and its reason. `durationMs` is the remaining overlap window; `lateMs`
records preparation delay beyond the planned start.

The `outgoing={...}` and `incoming={...}` groups snapshot cached planning inputs:

| Field | Meaning |
| --- | --- |
| `bpm`, `bpmConfidence` | Beat-grid tempo and periodicity confidence. Outgoing prefers the tail grid; incoming uses the head grid. |
| `beatPhaseMs` | Track-relative fitted grid phase, not a musical downbeat. |
| `downbeatConfidence`, `phraseConfidence` | Bar-level downbeat contrast and 8/16-bar phrase boundary energy contrast merged onto the extended grid. |
| `key`, `keyConfidence` | Local pitch name and major/minor mode, such as `G:minor`, with heuristic confidence. Unknown keys retain their reason. |
| `keyWindowMs` | Source interval covered by the key estimate. Independent head-key analysis is used only if transition features are absent. |
| `featureWindowMs`, `featureStatus` | Source interval and validity of the transition features. |
| `rms`, `rhythmStability` | RMS amplitude and energy-envelope correlation at the beat period. |
`bpmMethod=onset_acf_wls` names onset analysis, autocorrelation tempo candidates,
and weighted least-squares grid fitting. `keyMethod=chroma_profile` names chroma
correlation against major/minor profiles. `limits` contains the planner's minimum
beat confidence and maximum allowed tempo-ratio change. `unknown` values mean
no usable measurement exists. These scores are not calibrated probabilities.

Progress, buffer starvation, late-start details, and handoff positions use DEBUG.
Enable them in Lavalink's `application.yml` when investigating a transition:

```yaml
logging:
  level:
    io.github.mochilabs.mixer.GuildMixer: DEBUG
```

An abort or playback error still produces a warning. A native failure after the
start summary can change the final beat-match result; inspect the warning and
`overlapEnd` fallback fields in that case.

## Maintain build and API metadata

Version resolution uses `MIXER_VERSION`, then `-Pversion`, then an exact Git
tag, then `0.0.0-SNAPSHOT`. A leading `v` is removed. To build a specific version:

```bash
./gradlew build -Pversion=1.2.3
```

If `MIXER_VERSION` is set, it takes precedence over the property.
`processResources` replaces `@version@` in the plugin descriptor.
The Maven artifact ID is `mixer-plugin`; the local JAR basename follows
the project name `lavalink_mixer`.

CI build jobs use Java 17 and `./gradlew build`. Markdown-only changes are
excluded by the build workflow. Release workflows attach JARs to GitHub
releases and publish the Maven artifact to GitHub Packages.

When changing the protocol, update handler DTOs and mappings, controller logic,
tests, `api/openapi.yaml`, and the v2 bot integration guide together. Check both
REST responses and WebSocket event payloads. Treat the schema as the exact
current contract; remove retired fields rather than retaining compatibility
aliases.
