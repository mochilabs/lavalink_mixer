# lavalink_mixer

A standalone [Lavalink v4](https://github.com/lavalink-devs/Lavalink) plugin:
**gapless queue + overlap crossfade + TTS/announcement overlay with ducking +
leading/trailing silence skipping**.
No changes to the Lavalink codebase required.

Built against `dev.arbjerg.lavalink:plugin-api:4.2.2` + `dev.arbjerg:lavaplayer:2.2.6`
(Java 17). Protocol Version 2 (JIT Prefetch & Generation Epoch Fencing).

- Machine-readable REST spec for agents/tools: [`api/openapi.yaml`](api/openapi.yaml)
- Bot-side wiring for Tomato6966/lavalink-client: [`examples/lavalink-client/`](examples/lavalink-client/)

## Why Protocol v2 (JIT Prefetch & Epoch Fencing)

In v1 the queue contract was naive: `POST /mixer/queue/next` at track start,
then the server drove the handoff. This caused **race conditions**:
the 1-ahead preload window (often 3-4 minutes) was fully open to queue
mutations, and a user skip while the plugin armed a track created a double
play or ghost track.

V2 solves this with a **state machine based on Just-In-Time (JIT) prefetch and
monotonic Epoch fencing**:

- The server never preloads at track start. It waits until the audio engine
  approaches `crossfadeMs + 7000ms` remaining, then fires a
  `MixerNeedNextTrackEvent` to ask the client for the next track.
- The client arms the next track atomically with a unique `epoch` and
  `Idempotency-Key`.
- If the user changes the queue before the handoff, the client submits an
  `invalidate` request with a *higher epoch*; any pending ARMED slot is
  dropped, and the server re-fires the JIT window.
- If the user skips, the client submits an `override` request; the server
  clears the ARMED slot and stops/starts the main player atomically on its own
  audio thread without the client having to call `play()`.

This guarantees **exactly one state transition per track lifecycle** and
removes all double-play or ghost-track edge cases.

## Quickstart

1. Build and install:
   ```bash
   ./gradlew jar   # -> build/libs/lavalink_mixer-<version>.jar
   ```
   Copy the jar into the server's `plugins/` dir (or declare
   `dev.lavalink.mixer:mixer:1.0.0` in `application.yml`) and restart Lavalink.
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
  analysisThreads: 2
  maxParallelOverlays: 4
```

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
| `ARMED` | Next track preloaded & analysis complete |
| `NO_NEXT` | Playing, but queue is empty or client declined a JIT request |

## REST (v2 Protocol, `/mixer/*`)

All v2 endpoints expect the `Idempotency-Key` header.
The `Idempotency-Key` is scoped to a guild and deduplicated by payload fingerprint.
If the same key is replayed with a different payload, the request is rejected with `HTTP 422`.

| Method | Path | Headers | Body | Effect |
|---|---|---|---|---|
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

### Standard v2 errors
- `HTTP 400` Bad Request — missing or invalid `Idempotency-Key`, invalid action, invalid `encodedTrack`, or payload form mismatch.
- `HTTP 409` Conflict — `STALE_EPOCH`, or `PRE_RESET_ARRIVAL` with `{ "error": "PRE_RESET_ARRIVAL", "v2WindowOpenAt": <ms-epoch>, "windowOpenAt": <ms-epoch>, "lastKnownEpoch": <n> }`.
- `HTTP 422` Unprocessable Entity — payload mismatch for the same `Idempotency-Key`.

### Response fields (v2)
- `GET /mixer/state` includes `protocolVersion: 2`, `state` (identical to `playbackState`: `"IDLE" | "PLAYING" | "ARMED" | "NO_NEXT"`), `playbackState` (raw enum name, v1-compatible), and `lastKnownEpoch`.
- `POST /mixer/reset` returns `{ "lastKnownEpoch": <n>, "protocolVersion": 2 }`.
- `MixerAdvancedEvent` includes `handoffId` only for `handoffType: "CROSSFADE"`.

## WebSocket events (`op: "mixer"`, pushed by the plugin)

- `{"op":"mixer","type":"MixerNeedNextTrackEvent","guildId":"<id>","remainingMs":<ms>,"currentTrackEncoded":"<base64>"}` — JIT window fired, client should arm the next track.
- `{"op":"mixer","type":"MixerAdvancedEvent","guildId":"<id>","epoch":<n>,"encodedTrack":"<base64>","handoffType":"<CROSSFADE|GAPLESS>","handoffId":"<uuid>"}` — playback advanced server-side; client must sync its local queue.
- `{"op":"mixer","type":"MixerOverriddenEvent","guildId":"<id>","epoch":<n>,"requestId":"<key>","action":"<STOP|PLAY_NOW>"}` — override executed on the audio engine.
- `{"op":"mixer","type":"overlapStart|overlapEnd|announceStart|announceEnd", ...}` — traditional overlay & crossfade events. See `api/openapi.yaml`.

## Behavior notes / limits

- **Efficiency**: the mixer filter allocates no sub buffer unless the
  secondary gain is active. Analysis streams the head decode and stops as
  soon as the first sound is confirmed (typical tracks decode ~1-2s instead
  of the full window); short tracks are analyzed in a single pass; analyses
  run on a bounded pool (`mixer.analysisThreads`, default 2).
- **Silence skipping** (opt-in via `/mixer/silence` or yml): preloaded tracks
  sit in a pending slot while a background decode scans leading / trailing
  silence below `thresholdDb` (sustained `minSoundMs` to ignore clicks),
  then promote to ready. Leading silence is skipped with a seek when the
  track starts; trailing silence ends the track early (drives gapless
  advance and the crossfade trigger). A **realtime fallback** watches
  main-chain energy and cuts after `tailConfirmMs` of sustained silence
  inside the tail window — no extra decode, and the only signal that works
  for streams. If analysis is still running at content end the server
  advances anyway (no trim) rather than playing silence.
- **Volume normalization** (`/mixer/normalization`): measured using the ITU-R
  BS.1770-4 (EBU R128) K-weighting filter with 400ms momentary blocks,
  75% overlap, and a two-stage gating process (`-70 LUFS` absolute, `-10 LU`
  relative to absolute). The target loudness (`targetLufs`, default -14.0
  LUFS) is applied with `MAX_GAIN = 1.0` (no boost into clipping) unless
  explicitly overridden. The K-weighting filter is strictly applied to a
  measurement copy of PCM; the output signal is only scaled by the resulting
  linear gain.
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
