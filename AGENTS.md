# Working on lavalink_mixer

This repository builds a Lavalink v4 plugin in Kotlin. It mixes a secondary
audio source into the main player's PCM for crossfades and announcements.
It also supports silence trimming, loudness normalization, and beat matching.

## Start here

1. Read [the architecture reference](docs/architecture.md) for ownership,
   threading, audio flow, and protocol details.
2. Read [the development guide](docs/development.md) for build commands and
   tests relevant to your change.
3. For public behavior, inspect [the REST specification](api/openapi.yaml),
   `MixerRestHandler.kt`, and `ProtocolController.kt` together.

Treat executable code and tests as evidence of current behavior. The dated
document under `docs/plans/` is a design plan, not an implementation checklist.
Beat matching already exists.

## Find the implementation

Production code is under `src/main/kotlin/io/github/mochilabs/mixer/`.
The `audio/`, `analysis/`, and `protocol/` directories all use the same Kotlin
package, `io.github.mochilabs.mixer`. Preserve that convention when adding files.

- `MixerPlugin.kt`: guild registry, shared scheduler, analysis executor, track loading.
- `GuildMixer.kt`: player ownership, queue slots, crossfade lifecycle, gains, overlays.
- `MixerEvents.kt`: Lavalink player lifecycle and WebSocket event dispatch.
- `protocol/`: REST requests, epoch checks, idempotency, and ordered control actions.
- `audio/`: PCM mixing, queues, gain ramps, and tempo stretching.
- `analysis/`: isolated analysis decoding, caches, silence, loudness, and beat grids.
- `MixerConfig.kt`: startup defaults bound under `mixer:` in Lavalink configuration.
- `src/test/kotlin/io/github/mochilabs/mixer/`: unit and local audio integration tests.

## Preserve these constraints

- Never acquire `playerMutationLock` while holding the guild state `lock`.
  Plan transitions under `lock`, release it, and use the existing player
  mutation path. Recheck epochs, sequence numbers, and track identity as needed.
- Keep decoding, track resolution, analysis, and blocking waits out of
  `MixerFilter.process`. Analysis owns a separate player and siphon queue.
- Always forward processed PCM to the downstream filter, including passthrough
  and mixer-error paths. Lavaplayer dispatches lifecycle callbacks itself.
- Resolve the guild mixer lazily in the filter. A reconnect can replace the
  mixer after the filter chain was built.
- Preserve feeder election. Only the elected filter may advance gains,
  consume shared secondary PCM, or update the realtime silence detector.
- Keep siphon capacity in samples. Decoder chunk sizes differ by source.
  Preserve zero-filled underflow and bounded overflow behavior.
- Calculate handoff position from consumed secondary source samples, including
  tempo adjustment. Decoder position can run ahead of audible output.
- Preserve native tempo-stretch failure fallback and ordinary crossfade fallback.
- Keep guild IDs as strings in JSON. Preserve protocol-v2 event names and
  compatibility fields unless the task explicitly changes the contract.
- Keep Lavalink, Lavaplayer, and lavadsp dependencies `compileOnly` in production.
  Lavalink supplies them at runtime. Tests declare their own runtime dependencies.

## Validate and maintain the docs

Run the relevant tests during implementation, then `./gradlew build` for code
changes. Report failures and environment limits accurately. A local audio test
does not prove playback through a live Discord voice connection.

For REST or event changes, update `api/openapi.yaml`, the README, and affected
client examples. For architecture or workflow changes, update these AI-facing
docs. Do not assume the Gradle build type-checks `examples/lavalink-client/`.
