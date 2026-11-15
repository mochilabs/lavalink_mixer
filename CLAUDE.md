# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## Commands

Run from the repository root. CI uses JDK 17; Gradle is pinned by the wrapper.

```bash
./gradlew build -Pversion=1.2.3       # explicit version (MIXER_VERSION env var wins)
./gradlew test --tests 'io.github.mochilabs.mixer.GuildMixerProtocolTest'
./gradlew test --tests 'io.github.mochilabs.mixer.*BeatMatch*'
```

A full build takes about 4 minutes; run it in the background. There is no
separate lint task. `docs/development.md` has a table mapping each kind of
change to the test classes worth running first.

Test notes: integration tests generate local WAV files and drive real
Lavaplayer players (no Discord needed). Some load native audio libraries, so
tell native-loading failures apart from behavioral ones. Analysis caches are
shared, so new analysis tests need unique track identifiers. Release players
and executors in cleanup.

## Gotchas

- `CrossfadeEngine`, `OverlayController`, and `AnalysisCoordinator` have no
  locks of their own; they use `GuildMixer.lock` through their host interfaces.
  A method named `...Locked` must be called with that lock held.
- Staleness checks are distinct and not interchangeable: epoch (client work),
  `advanceSeq` (playback advances), slot revisions and sub-player generations
  (replacements), and track-object identity (main track changed).

Read `docs/architecture.md` for diagrams of the lock order, audio graph,
crossfade lifecycle, and the three playback positions before touching
`GuildMixer`, `CrossfadeEngine`, or the audio queues.

## Repository conventions

- Docs exist in English and Korean (`*.ko.md`). Update both when changing a
  documented behavior.
- Protocol changes: update handler DTOs, `ProtocolController`, tests,
  `api/openapi.yaml`, the README, and `examples/lavalink-client/` together.
  Remove retired fields rather than keeping compatibility aliases.
- Live playback is verified manually by copying the JAR into a Lavalink
  server's `plugins/` directory (steps in `docs/development.md`).
