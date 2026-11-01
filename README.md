# lavalink_mixer

A standalone [Lavalink v4](https://github.com/lavalink-devs/Lavalink) plugin:
**gapless queue + overlap crossfade + TTS/announcement overlay with ducking +
leading/trailing silence skipping**.
No changes to the Lavalink codebase required.

Built against `dev.arbjerg.lavalink:plugin-api:4.2.2` + `dev.arbjerg:lavaplayer:2.2.6`
(Java 17). Protocol Version 2 (JIT Prefetch & Generation Epoch Fencing).

- Machine-readable REST spec for agents/tools: [`api/openapi.yaml`](api/openapi.yaml)
- Bot integration: [`examples/lavalink-client/README.md`](examples/lavalink-client/README.md)
- AI contributor instructions: [`AGENTS.md`](AGENTS.md)
- Implementation reference: [`docs/architecture.md`](docs/architecture.md)
- Build and verification guide: [`docs/development.md`](docs/development.md)

## Queue protocol: JIT prefetch and epoch fencing

The server asks the client for the next track only when playback approaches
the transition window (`crossfadeMs + 7000ms` remaining). The client arms that
track with a monotonically increasing epoch and an `Idempotency-Key`. Queue
changes invalidate the armed slot with a higher epoch; user stop/skip commands
use an atomic server override. The server owns automatic transitions, so the
client must keep `autoSkip: false` and synchronize its queue from
`MixerAdvancedEvent`.

## Quickstart

1. Build and install:
   ```bash
   ./gradlew jar   # -> build/libs/lavalink_mixer-<version>.jar
   ```
   Copy the JAR into the server's `plugins/` directory, or declare
   `dev.lavalink.mixer:mixer-plugin:0.3.3` in Lavalink's plugin dependency
   configuration, then restart Lavalink.
2. Enable the filter **once per guild** (required for any audible effect):
   ```json
   { "op": "filters", "guildId": "123", "pluginFilters": { "mixer": { "guildId": "123" } } }
   ```
   The `guildId` inside `pluginFilters.mixer` binds the filter to the guild's
   mixer state (the extension API passes no player identity to filters).
   Send `guildId` as a **string** — snowflakes exceed 2^53.

Optional `application.yml` defaults:

```yaml
mixer:
  crossfadeEnabled: true
  crossfadeMs: 5000
  fadeInMs: 300
  duckLevel: 0.2
  duckFadeMs: 300
  maskMs: 80
  normalizationEnabled: false
  targetLufs: -14.0
  silenceSkipEnabled: false
  silenceThresholdDb: -60
  silenceMinSoundMs: 120
  silenceHeadScanMs: 15000
  silenceTailScanMs: 15000
  tailConfirmMs: 1000
  bpmDetectionEnabled: false
  bpmScanMs: 30000
  beatmatchEnabled: false
  maxTempoChange: 0.06
  beatmatchMinConfidence: 0.30
  analysisThreads: 2
  maxParallelOverlays: 4
```

### Adaptive transitions

AutoMix evaluates bounded head/tail candidates using energy, rhythm stability,
local key candidates, beat phase and tempo cost. It chooses boundary preservation,
silence trimming, a short crossfade, or beat matching. Unknown analysis falls back
conservatively; these are inspectable heuristics, not Apple's proprietary model.
Alternate nonsilent candidate points skip at most 2 seconds at either boundary.
Key confidence and energy thresholds still need real-music calibration.

A beat-matched plan reserves time inside `crossfadeMs` for incoming-only tempo
recovery. The outgoing gain reaches zero before recovery begins; incoming speed
smoothly returns to 1.0 before main-player handoff. The envelope follows generated
audio coordinates rather than a wall timer, including decoder prefill. Native
buffering means source-position accounting is an integrated estimate; failure
falls back without replaying an already emitted block, but a hidden native tail
cannot be recovered through the current lavadsp API.

`GET /mixer/state` advertises `adaptiveTransitionsSupported` and reports
`transitionKind`, `decisionReason`, and nullable `currentTrackBpm` with
`currentTrackBpmIdentifier`. Match that identifier to the client's confirmed
current track. BPM describes cached original audio, not the temporary playback speed.

Clients can warm the next candidate with `POST /mixer/analyze` and
`{"guildId":"123","encodedTrack":"..."}`. A 202 `accepted` receipt is best-effort;
arming remains on the protocol-v2 JIT path. Queue changes never arm warmed tracks.
For explicit continuous album playback, add `preserveTransition:true` to
`/mixer/queue/arm`. This boundary uses no overlap, trim, or incoming fade-in.
Do not infer album intent from matching artist/title strings.

See [adaptive calibration](docs/adaptive-calibration.md) for local pair reports.

### Enable beat-matched AutoMix

Beat matching is opt-in. It requires the mixer filter and all three settings:
`crossfadeEnabled`, `bpmDetectionEnabled`, and `beatmatchEnabled`.
Enable these before arming tracks so analysis has time to finish.

For one guild, send these requests to the Lavalink server with its normal
`Authorization` header and `Content-Type: application/json`:

```http
POST /mixer/crossfade
{"guildId":"123","enabled":true,"durationMs":5000}

POST /mixer/bpm
{"guildId":"123","enabled":true,"beatmatchEnabled":true,"scanMs":30000,"maxTempoChange":0.06,"minConfidence":0.30}
```

`POST /mixer/beatmatch` changes beat matching without changing BPM detection.
Calling it with `enabled: true` alone does not enable analysis.
These configuration requests do not require an `Idempotency-Key`.
Check the three flags in `GET /mixer/state?guildId=123` after configuration.

An enabled feature does not guarantee a beat-matched transition. Missing grids,
low confidence, excessive tempo difference, or an unsuitable fade window use
ordinary equal-power crossfade when available. For example, `bpm_disabled`
means BPM detection is off, and `missing_outgoing_grid` means no outgoing grid
was available when the transition was planned. Inspect `beatMatched` and
`fallbackReason` on the overlap events. If crossfade is disabled or cannot be
planned, playback uses the existing non-overlap advance path.

## How it works

Discord voice carries a single Opus stream per connection, so a second
audible source must be mixed **before encoding**. The plugin:

1. Creates a hidden secondary Lavaplayer `AudioPlayer` per guild (from the
   shared `AudioPlayerManager`, injected as a bean). It never touches voice.
2. Siphons its decoded float PCM into a bounded queue (`SiphonFilter`); a
   shared pump thread drains the decoder so it keeps flowing.
3. Mixes that PCM into the main player's filter chain via the `"mixer"`
   `AudioFilterExtension` — Lavaplayer feeds the chain head from the decoder,
   so the mixer runs first on raw PCM (before volume/EQ filters), and mixed
   chunks are forwarded through the rest of the chain to the frame buffer:
   `out = main * mainGain + sub * subGain`, clamped.
4. Handles track transitions via the state machine in `GuildMixer.kt`.
   The engine is single-threaded (`actionQueue`) under the plugin `lock`, so
   Lavaplayer `play()`/`stop()` calls never deadlock the voice thread.

## Handoff protocol states

| State | Meaning |
|---|---|
| `IDLE` | No track playing |
| `PLAYING` | Main track playing, no next track armed |
| `ARMED` | Next track accepted; analysis may still be pending |
| `NO_NEXT` | Playing, but queue is empty or client declined a JIT request |

## REST (v2 Protocol, `/mixer/*`)

Idempotency keys are required for `queue/arm`, `queue/invalidate`,
`queue/override`, and `reset`; they are scoped to a guild and deduplicated by
payload fingerprint. Reusing a key with a different payload returns `HTTP 422`.

| Method | Path | Headers | Body | Effect |
|---|---|---|---|---|
| POST | `/mixer/analyze` | `{guildId, encodedTrack}` | – | Best-effort cache-only warmup, 202 `{accepted}` |
| GET | `/mixer/info` | — | – | Returns `{ protocolVersion: 2, version }` |
| POST | `/mixer/queue/arm` | `Idempotency-Key` | `{guildId, epoch, encodedTrack?, isEmpty}` | Arms the next track; advances state to `ARMED` or `NO_NEXT` |
| POST | `/mixer/queue/invalidate` | `Idempotency-Key` | `{guildId, epoch}` | Clears the `ARMED` slot; if still `PLAYING`, tells server to re-evaluate JIT (fires `refiredNeedNext`) |
| POST | `/mixer/queue/override` | `Idempotency-Key` | `{guildId, epoch, action, encodedTrack?}` | `action` = `"STOP"` or `"PLAY_NOW"`. Atomically clears slot and issues engine mutation. |
| POST | `/mixer/reset` | `Idempotency-Key` | `{guildId, epoch, windowOpenGraceMs?}` | Resyncs epoch, drops all request IDs, and sets `windowOpenAt = now + graceMs` to prevent stale arm |
| GET | `/mixer/state?guildId=` | — | – | Mixer state + config |
| POST | `/mixer/crossfade` | — | `{guildId, enabled, durationMs?}` | Overlap crossfade (500–30000ms) |
| POST | `/mixer/fade-in` | — | `{guildId, durationMs}` | Fade-in on track start (0–10000ms, 0=off) |
| POST | `/mixer/announce` | — | `{guildId, identifier?, encodedTrack?, duckLevel?, mode?}` | Queue an overlay |
| DELETE | `/mixer/announce/queue?guildId=` | — | – | Drop all queued announcements |
| POST | `/mixer/announce/cancel` | — | `{guildId}` | Stop overlay, restore music |
| POST | `/mixer/normalization` | — | `{guildId, enabled, targetLufs?}` | Volume normalization on/off (target clamped to [-30, -6]) |
| POST | `/mixer/silence` | — | `{guildId, enabled, ...}` | Leading/trailing silence skipping |
| POST | `/mixer/bpm` | — | `{guildId, enabled, scanMs?, beatmatchEnabled?, maxTempoChange?, minConfidence?}` | Tempo (BPM) detection on/off (scan window clamped to [5000, 60000] ms) |
| POST | `/mixer/beatmatch` | — | `{guildId, enabled, maxTempoChange?, minConfidence?}` | Beat-matched crossfade on/off (max tempo shift default 0.06, min confidence default 0.30) |

### Standard v2 errors
- `HTTP 400` Bad Request — missing or invalid `Idempotency-Key`, invalid action, invalid `encodedTrack`, or payload form mismatch.
- `HTTP 409` Conflict — `STALE_EPOCH`, or `PRE_RESET_ARRIVAL` with `{ "error": "PRE_RESET_ARRIVAL", "windowOpenAt": <ms-epoch>, "lastKnownEpoch": <n> }`.
- `HTTP 422` Unprocessable Entity — payload mismatch for the same `Idempotency-Key`.

### Response fields (v2)
- `GET /mixer/state` includes `protocolVersion: 2`, `state` (`IDLE`, `PLAYING`,
  `ARMED`, or `NO_NEXT`), and `lastKnownEpoch`.
- `POST /mixer/reset` returns `{ "lastKnownEpoch": <n>, "protocolVersion": 2 }`.
- `MixerAdvancedEvent` includes `handoffId` only for `handoffType: "CROSSFADE"`.
- `/mixer/state` reports transition configuration and analysis, including
  `nextTrackBpmConfidence` and `nextTrackPhaseMs` (not a musical downbeat).

## WebSocket events (`op: "mixer"`, pushed by the plugin)

- `{"op":"mixer","type":"MixerNeedNextTrackEvent","guildId":"<id>","remainingMs":<ms>,"currentTrackEncoded":"<base64>"}` — JIT window fired, client should arm the next track.
- `{"op":"mixer","type":"MixerAdvancedEvent","guildId":"<id>","epoch":<n>,"encodedTrack":"<base64>","handoffType":"<CROSSFADE|GAPLESS>","handoffId":"<uuid>"}` — playback advanced server-side; client must sync its local queue.
- `{"op":"mixer","type":"MixerOverriddenEvent","guildId":"<id>","epoch":<n>,"requestId":"<key>","action":"<STOP|PLAY_NOW>"}` — override executed on the audio engine.
- `{"op":"mixer","type":"overlapStart","guildId":"<id>","crossfadeMs":<ms>,"handoffInMs":<ms>,"beatMatched":<bool>,"fadeBeats":<n>,"targetBpm":<bpm>,"rateRatio":<ratio>,"fallbackReason":"...", ...}` — overlap started.
- `{"op":"mixer","type":"overlapEnd","guildId":"<id>","reason":"handoff"|"aborted","beatMatched":<bool>,"fallbackReason":"...", ...}` — overlap ended or aborted.
- `{"op":"mixer","type":"announceStart|announceEnd", ...}` — announcement events. See `api/openapi.yaml`.

## Behavior notes / limits

- **Efficiency**: the mixer filter allocates no sub buffer unless the
  secondary gain is active. Analysis streams the head decode and stops as
  soon as the first sound is confirmed (typical tracks decode ~1-2s instead
  of the full window); short tracks are analyzed in a single pass; analyses
  run on a bounded pool (`mixer.analysisThreads`, default 2).
- **Silence skipping** (opt-in via `/mixer/silence` or yml): preloaded tracks
  are decoded on an isolated analysis player to find leading/trailing silence.
  Leading silence is skipped on seekable tracks. Trailing silence ends playback
  at the measured content boundary; if analysis is unavailable, realtime PCM
  detection waits until the current playback position reaches confirmed silence
  within the configured tail window. It also starts early enough to avoid
  crossfading over confirmed silence. The realtime path is the only signal for
  streams. Enable it with `mixer.silenceSkipEnabled: true` or
  `POST /mixer/silence` (`{"guildId":"...","enabled":true}`); default threshold
  is -60 dB and confirmation is 1000 ms.
  If analysis is still running at content end the server advances without trim.
- **Volume normalization** (`/mixer/normalization`): measured using the ITU-R
  BS.1770-4 (EBU R128) K-weighting filter with 400ms momentary blocks,
  75% overlap, and a two-stage gating process (`-70 LUFS` absolute, `-10 LU`
  relative to absolute). The target loudness (`targetLufs`, default -14.0
  LUFS) is applied with `MAX_GAIN = 1.0` (no boost into clipping) unless
  explicitly overridden. The K-weighting filter is strictly applied to a
  measurement copy of PCM; the output signal is only scaled by the resulting
  linear gain.
- **Beat-matched AutoMix & Tempo detection** (`/mixer/bpm`, `/mixer/beatmatch`):
  A preloaded track decodes head and tail `bpmScanMs` windows (default 30s) and runs a pure-Kotlin
  energy-flux onset + lag autocorrelation to estimate tempo (BPM) and beat grid phase offset (ms),
  exposed through `/mixer/state` as `nextTrackBpm` and `nextTrackPhaseMs`.
  With crossfade, BPM detection, and beat matching enabled, outgoing and incoming beat grids align kicks across 2, 4, or 8
  beats using producer-side timescale time-stretching (`SoundTouch` via `TempoStretcher`) within `maxTempoChange` (clamped up to 0.12).
  If tempo difference exceeds `maxTempoChange` or confidence is below `minConfidence`, it cleanly falls back
  to equal-power crossfading (`fallbackReason` reported on `overlapStart`/`overlapEnd`).
- **Crossfade mixes are soft-limited**: the equal-power sum of two hot
  sources can exceed full scale (up to ~1.41× at the midpoint). Instead of
  hard-clipping that sum into a square wave, only the excursion past a 0.9
  knee is compressed smoothly toward full scale — a 1.41× peak lands at
  ~0.98, an inaudible ~0.14dB reduction.
- **Seek & stop safety**: seeking backwards during an armed overlap, or
  client-side stop/replace, disarms the mix (`crossfade aborted` log). In
  v2, the plugin aborts the overlap at the state machine layer before
  dispatching a stop, preventing a handoff from firing into a stopped engine.
- `/mixer/state` reports `nextTrackIdentifier`/`nextTrackTitle`,
  `subTrackIdentifier`, `mixerFilterActive`, `crossfadeHandoffInMs`, so
  clients can verify which track the server will start next.
- **Announcements**: queued and play back to back with ducking. Announcements
  are handled on the single secondary player and never block the main JIT
  handoff path. `mode: "parallel"` uses an isolated overlay player with its
  own fade ramp.

## Developing

```bash
./gradlew build   # compiles + runs all tests
```

Requires Java 17+ and network access to Maven Central + `maven.lavalink.dev`
for `plugin-api` / `lavaplayer` compile-only deps.

## License

Public domain under the [UNLICENSE](UNLICENSE).
