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
./gradlew test --tests 'dev.lavalink.mixer.plugin.GuildMixerProtocolTest'
./gradlew test --tests 'dev.lavalink.mixer.plugin.*BeatMatch*'
```

Use these existing tests as starting points:

| Change | Relevant test classes |
| --- | --- |
| Epochs, idempotency, reset, override | `GuildMixerProtocolTest` |
| Stale transitions and handoff | `StaleXfadeAbortTest`, `CrossfadeMixIntegrationTest`, `XfadeVoicePathTest` |
| Filter chain binding and reconnects | `MixerFilterExtensionTest`, `ChainDiagnosticTest`, `FeederArbitrationTest`, `FirstTrackIntegrationTest` |
| PCM and gain behavior | `MixerFilterTest`, `GainRampTest`, `SiphonQueueTest` |
| Announcements | `AnnounceQueueTest` |
| Analysis ownership and retries | `AnalysisIsolatedFromLiveSubTest`, `DeferredAnalysisTest` |
| Silence and trim | `SilenceAnalyzerTest`, `TailCutDeciderTest`, `TrimTransitionTest` |
| Loudness | `NormalizationGainTest`, `NormalizationBaselineTest`, `NormalizationIntegrationTest` |
| BPM and beat matching | `BpmDetectorTest`, `BpmGridTest`, `BpmIntegrationTest`, `BeatMatchPolicyTest`, `BeatMatchIntegrationTest`, `BeatMatchOutputTest`, `TempoStretcherTest`, `TempoEnvelopeTest`, `AdaptiveRecoveryIntegrationTest`, `AdaptiveRuntimeTest`, `AdaptiveTransitionRaceTest`, `TransitionFeaturesTest`, `AdaptiveTransitionPlannerTest` |

Integration tests generate local WAV files and exercise Lavaplayer players.
They do not require a running Discord bot. Some tests exercise native audio
libraries, so distinguish native-loading failures from behavioral failures.
Use unique track identifiers in new analysis tests because caches are shared.
Release players and executors in test cleanup.

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
tests, `api/openapi.yaml`, and affected README examples together. Check both
REST responses and WebSocket event payloads. Preserve compatibility fields
such as `playbackState` and `nextTrackFirstBeatMs` when adding replacements.
