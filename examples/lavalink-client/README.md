# Bot integration: Mixer protocol v2

This guide describes the only supported queue-control protocol. The plugin owns
track transitions after the first track starts. The bot must process mixer
WebSocket events, arm the next track just in time, and synchronize its local
queue from server events.

## 1. Install the plugin

Copy `mixer-*.jar` into Lavalink's `plugins/` directory and restart the server.
Verify the plugin appears in `GET /version`.

Optional server defaults in `application.yml`:

```yaml
mixer:
  crossfadeEnabled: true
  crossfadeMs: 5000
  fadeInMs: 300
  duckLevel: 0.2
  duckFadeMs: 300
  silenceSkipEnabled: false
  silenceThresholdDb: -60
  silenceMinSoundMs: 120
  silenceHeadScanMs: 15000
  silenceTailScanMs: 15000
  tailConfirmMs: 1000
  normalizationEnabled: false
  targetLufs: -14.0
  bpmDetectionEnabled: false
  bpmScanMs: 30000
  beatmatchEnabled: false
  maxTempoChange: 0.06
  beatmatchMinConfidence: 0.30
```

Per-guild settings can also be changed through `/mixer/*` REST endpoints.
Requests use Lavalink's normal `Authorization` header. Use string guild IDs in
JSON; Discord snowflakes are not safe as JavaScript numbers.

## 2. Enable the mixer filter

Send the Lavalink WebSocket filters op for each guild before starting playback:

```json
{
  "op": "filters",
  "guildId": "123456789012345678",
  "pluginFilters": {
    "mixer": { "guildId": "123456789012345678" }
  }
}
```

Preserve this `pluginFilters.mixer` entry when replacing the filter object. The
filter is required for crossfade, overlays, fade, and PCM mixing. Confirm it
with `GET /mixer/state?guildId=123456789012345678` and check
`mixerFilterActive`.

## 3. Configure optional features

Crossfade is on by default in plugin config, with a 5-second duration. Example
per-guild settings:

```http
POST /mixer/crossfade
{"guildId":"123456789012345678","enabled":true,"durationMs":5000}

POST /mixer/fade-in
{"guildId":"123456789012345678","durationMs":300}

POST /mixer/silence
{"guildId":"123456789012345678","enabled":true,"thresholdDb":-60,"tailConfirmMs":1000}

POST /mixer/normalization
{"guildId":"123456789012345678","enabled":true,"targetLufs":-14}

POST /mixer/bpm
{"guildId":"123456789012345678","enabled":true,"beatmatchEnabled":true,"scanMs":30000,"maxTempoChange":0.06,"minConfidence":0.30}
```

Beat-matched transitions require crossfade, BPM detection, and beat matching all
enabled. Measurements may not be ready for the first transition; the server
uses available trim data and otherwise falls back to ordinary crossfade.

## 4. Start the first track normally

Connect the player to voice, enable its mixer filter, then enqueue and play the
first track using your Lavalink client. Do not configure client auto-advance:
set `autoSkip: false`. The bot remains responsible for choosing and starting the
first track only.

## 5. Implement the JIT event flow

Forward raw Lavalink WebSocket messages to the bot's event handler. On
`MixerNeedNextTrackEvent`, choose the current next item and POST
`/mixer/queue/arm` to that node. The request's `encodedTrack` must be the exact
Lavalink-encoded track. If the bot queue is empty, send `isEmpty: true`.

```http
POST /mixer/queue/arm
Authorization: <Lavalink password>
Content-Type: application/json
Idempotency-Key: <unique key for this operation>

{"guildId":"123456789012345678","epoch":12,"encodedTrack":"<encoded track>"}
```

Maintain one monotonically increasing epoch per guild. Use a new idempotency
key for each logical operation; reuse it only when retrying that same request.
If the queue head changes after arming, call `/mixer/queue/invalidate` with a
higher epoch and a new key. The server can re-emit the JIT request when the
track is still in its transition window.

When the server sends `MixerAdvancedEvent`, remove/advance the corresponding
local queue item and update now-playing state. **Do not call `play()` for that
track**: it is already playing on the server. Never start a second track from a
client `trackEnd` handler. Keep `autoSkip: false` to prevent a client/server
race.

For an explicit user skip or stop, use `/mixer/queue/override` with a higher
epoch and a unique key. `action: "PLAY_NOW"` also includes the chosen track's
`encodedTrack`; `action: "STOP"` stops playback. Acknowledge completion from
`MixerOverriddenEvent`, not only from the HTTP acceptance response.

On bot startup or session resume, call `/mixer/reset` to synchronize the
server's epoch baseline. Follow `409` conflicts using the server's
`lastKnownEpoch` and, for reset grace conflicts, the returned `windowOpenAt`.

## Features available to the bot

- **Crossfade and gapless handoff**: server-managed overlap when configured;
  otherwise it advances without overlap.
- **Silence skipping**: leading/trailing silence analysis for seekable tracks;
  realtime tail detection is the fallback, including streams.
- **Fade-in and normalization**: per-guild fade duration and EBU R128 target.
- **BPM and beat-matched AutoMix**: opt-in analysis and temporary tempo stretch;
  unsuitable/missing grids fall back to normal crossfade.
- **Announcements**: POST `/mixer/announce`; DELETE
  `/mixer/announce/queue?guildId=...` clears waiting items; POST
  `/mixer/announce/cancel` stops an active overlay. Sequential overlays duck
  music; `mode: "parallel"` uses an isolated overlay player.
- **Diagnostics**: GET `/mixer/state?guildId=...` for queue, transition,
  feature, and analysis state. POST `/mixer/analyze` optionally warms analysis
  caches; it never arms a track or replaces normal queue handling.

For request schemas, bounds, events, and error bodies, see
[`api/openapi.yaml`](../../api/openapi.yaml). The plugin architecture and
verification commands are in [`docs/architecture.md`](../../docs/architecture.md)
and [`docs/development.md`](../../docs/development.md).
