# Architecture reference

## Runtime boundary

`lavalink_mixer` is a plugin loaded by a Lavalink server. There is no standalone
application entry point. Spring discovers the services in
`io.github.mochilabs.mixer` through the plugin descriptor at
`src/main/resources/lavalink-plugins/mixer-plugin.properties`.

The build uses Kotlin 2.1.20 and targets JVM 17. Its host dependencies are
Lavalink plugin API 4.2.2, Lavaplayer 2.2.6, and lavadsp 0.7.8.
These versions describe this repository's build, not the latest upstream releases.

## Ownership and execution

| Component | Responsibility |
| --- | --- |
| `MixerPlugin` | Stores mixers by guild ID and resolves encoded tracks or source identifiers. |
| `MixerEvents` | Attaches new Lavalink players and removes a mixer when its attached main player is destroyed. |
| `GuildMixer` | Owns main-player binding, secondary playback, next-track slots, gains, transitions, and overlay slots. |
| `ProtocolController` | Owns protocol state, armed-track metadata, JIT requests, and the ordered action executor. |
| `EpochGate` | Tracks the current epoch, reset grace window, and recent request fingerprints. |
| `TrackAnalysisService` | Owns a separate analysis player and capture filter for each guild. |
| `AnalysisCaches` | Shares identifier-keyed measurements across guilds. |

`MixerPlugin` schedules `drain()` every 20 ms and `poll()` every fifth tick.
The same scheduler checks analysis retries and queued announcements.
A fixed analysis pool has `analysisThreads` workers, clamped to 1–8.
Each protocol controller has a single scheduled executor named `mixer-v2-action`
for ordered actions and delayed JIT notifications.

Audio filter callbacks and player event callbacks also access mixer state.
The system is not confined to the protocol executor. `GuildMixer.lock` guards
compound state transitions, with volatile fields and atomics for selected values.
The outer `playerMutationLock` serializes main-player mutations across handoff,
gapless advance, and override. Acquiring it while holding `lock` violates the
documented lock order.

Player operations that can trigger callbacks occur outside the state lock in
the transition paths. Epoch, sequence, generation, and track-identity checks
reject work superseded while that lock was released.

## Audio flow

The main decoder sends PCM through `MixerFilter` and then downstream toward
Lavalink's output. The secondary decoder sends PCM through an optional
`TempoStretcher` and `SiphonFilter`. The siphon copies PCM into `SiphonQueue`
while also forwarding audio to its own downstream frame buffer. The scheduler
drains secondary output so decoding continues.

`MixerFilter` consumes captured secondary PCM, applies the main and secondary
gains, adds parallel overlays, and forwards the result. The mixing path uses
a soft limiter above an absolute amplitude of 0.9. Main-only attenuation has
a separate path. Idle audio passes through unchanged.

The filter is enabled per guild with `pluginFilters.mixer.guildId`. The ID is
a JSON string. The extension receives no player identity, so the ID binds
the filter to the guild registry. The filter looks up that registry on every
chunk, allowing a newly created or replacement mixer to receive audio.

Two filter chains can briefly refer to one guild during reconnects. Feeder
election lets only one chain consume the shared PCM and advance audio state.
Closing a filter retires its feeder ID.

`SiphonQueue` is bounded by samples per channel, rather than decoder chunks.
Overflow removes old queued audio, and underflow fills the missing portion
with zeroes. Its production capacity accounts for Lavaplayer frame-buffer prefill.
Each queued frame records the source-rate span attributed to its output. Native
output can be delayed, so the stretcher maintains a FIFO of submitted rate spans
rather than reading the most recently requested rate. Reads and discards use that
speed, so a later native failure does not reinterpret already queued audio
at the fallback speed.

At crossfade handoff, the incoming track is cloned into the main player.
Its seek position comes from consumed source samples plus its starting position.
Tempo-adjusted source progress differs from output sample count. Both differ
from decoder progress, which includes buffered audio not yet heard.
When polling starts an overlap late, secondary reads discard the missed portion
to align with the main sample timeline. Skipped source progress contributes
to the handoff seek, but not to the audible secondary sample counter.
An underrun preserves the remaining catch-up work for the next read.

## Protocol v2

`MixerRestHandler` exposes `/mixer/*`. The full request and response reference
is [api/openapi.yaml](../api/openapi.yaml). `MixerEvents.kt` contains the helper
that sends custom WebSocket messages with `op: "mixer"` to the main player's session.

| State | Implementation meaning |
| --- | --- |
| `IDLE` | Protocol state has no active playback. |
| `PLAYING` | Playback can request a next track through the JIT window. |
| `ARMED` | An incoming track has been accepted; analysis may still be pending. |
| `NO_NEXT` | The client has declared that no next track is available. |

The protocol's armed slot and the mixer's pending/ready analysis slots are
distinct. `arm()` sets `ARMED` before scheduling analysis. It does not imply
that analysis is complete.

`checkJitWindow()` requests the next track when the known duration has at most
`crossfadeMs + 7000` ms remaining. Short tracks receive a 500 ms settle delay.
The reset grace window can delay dispatch further. Sequence checks suppress
duplicate notifications and notifications for replaced tracks.

The bot answers with `/mixer/queue/arm`, invalidates a changed queue with
`/mixer/queue/invalidate`, and uses `/mixer/queue/override` for `STOP` or
`PLAY_NOW`. These operations and `/mixer/reset` require an `Idempotency-Key`.
Configuration endpoints do not require that header.

`EpochGate` retains up to 64 recent request fingerprints per guild. It checks
existing keys before checking the reset window and epoch. A matching fingerprint
is a duplicate; a different fingerprint is a payload mismatch. A new request
with an epoch below `lastKnownEpoch` is stale. Equal epochs are accepted.
Fingerprints are operation-specific strings, not hashes of the full JSON body.

Reset keeps the maximum of the previous and supplied epochs, clears prior
request keys, records its own key, and opens an optional grace window.
The default grace is 1500 ms. Idempotency is bounded, in-memory behavior,
not a durable exactly-once guarantee across restarts or cache eviction.

`MixerAdvancedEvent` reports server-driven advancement. The client must sync
its queue without independently starting the same next track. The bot wiring
guide requires `autoSkip: false`. Crossfade advances include a `handoffId`.

## Analysis and overlays

Silence detection, normalization, and BPM detection default to disabled.
Analysis decodes through `TrackAnalysisService`, separate from live secondary
playback. `AnalysisState` serializes scans per guild and limits repeated failures.
Pending tracks can advance without completed analysis when content ends.
BPM-only work can retry while the next track is already ready for playback.
A successful scan with no beat grid completes that scan rather than triggering
repeated decodes. The per-guild analysis state remembers the last such completion.

`AnalysisCaches` stores trim points, loudness, BPM, and head/tail beat grids.
Each cache has a coarse size bound of 1000 and uses the track identifier as its
key. The key does not include guild settings. Concurrent callers can repeat
analysis; the cache does not guarantee one decode per identifier.

`NormalizationGain` measures K-weighted loudness and calculates linear gain.
Measurement filtering does not replace output PCM. Normalization defaults to
attenuation only. Silence detection combines offline trim points with a realtime
tail detector on raw main PCM before gain application.

`TransitionFeatureAnalyzer` extracts bounded energy, rhythm stability, local chroma
key candidates and boundary candidates from the existing BPM PCM scans.
`AdaptiveTransitionPlanner` compares candidate pairs within a 2-second nonsilent
skip budget, and selects preserve, silence trim, short crossfade or beat matching.
The thresholds are conservative heuristics awaiting representative music calibration.
`BeatMatchPolicy` chooses compatible beat grids and a 2-, 4-, or 8-beat fade.
`CrossfadePlan.overlapMs` controls gain fading; `recoveryMs` reserves an incoming-only
speed recovery inside the total window. `TempoEnvelope` runs on projected output
sample coordinates in the secondary producer, so decoding ahead does not shift
its schedule. Keeping the native delegate alive at unity avoids discarding its tail.
Exact native per-output input positions are unavailable: FIFO source integration
is tested to a tolerance, not claimed sample-exact WSOLA correspondence.

`requestPreanalysis` bounds queued warmup to one per guild and writes only BPM and
feature caches. It never arms, promotes or changes live gain. Current-track BPM
analysis can run without a queued next track. Results are track-keyed; normal JIT
arming and epoch checks remain the authority for playback. Explicit preservation
is an optional arm field included in the idempotency fingerprint, never a global
sticky album mode.
`TempoStretcher` applies producer-side speed changes using lavadsp. Low confidence,
incompatible tempos, and unavailable grids can fall back to ordinary crossfade.
Native stretching failures fall back to forwarding unstretched PCM.
Beat phase is not a musical downbeat. Enabling beat matching does not by itself
enable BPM analysis in `updateBeatmatch()`.

`BpmDetector` normalizes energy and spectral analysis levels, retains local
autocorrelation tempo candidates, and fits each candidate's period/phase with WLS.
Tempo selection combines duration-aware accent periodicity, coverage of those
accents by the fitted grid, and a log-tempo prior. Onset timing weights remain
separate from metrical salience. Nearby integer BPM values are accepted only when the same assigned onsets support their residual.
The first energy onset remains distinct from fitted grid phase. Confidence is
periodicity, not metrical-level certainty. The detector remains pure Kotlin/JVM;
its [research and calibration notes](bpm-detector-design.md) describe policy values
and the strong-hi-hat octave regression checks. The default scan stays at 30 seconds.

Sequential announcements share the secondary player with crossfades.
`AnnouncementQueue` defers track resolution until playback and can requeue
an item if the secondary player becomes busy. Parallel announcements own
separate players, queues, and gain ramps. Their count defaults to four and
is clamped to 1–16 per guild.

## Known documentation and implementation differences

- The README describes `ARMED` as analysis complete. The implementation accepts
  the arm before analysis finishes.
- The README describes the engine as single-threaded. The state lock coordinates
  several callback and executor threads.
- `/mixer/info` returns the version resolved from the packaged plugin
  descriptor, which Gradle fills from the build version; the value matches the
  running JAR.
- The beat-matching plan describes work that is already implemented. Current
  source and tests establish what is present.
