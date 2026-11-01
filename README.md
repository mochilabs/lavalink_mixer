# lavalink_mixer

[![Release](https://img.shields.io/github/v/release/mochilabs/lavalink_mixer?color=blue&label=release)](https://github.com/mochilabs/lavalink_mixer/releases/latest)
[![License](https://img.shields.io/badge/license-Unlicense-green)](UNLICENSE)
[![Lavalink](https://img.shields.io/badge/Lavalink-v4-orange)](https://github.com/lavalink-devs/Lavalink)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net)

A high-performance [Lavalink v4](https://github.com/lavalink-devs/Lavalink) plugin that brings DJ-style audio mixing, smart transitions, and audio enhancements directly to the server side:

- 🎧 **Beat-Matched AutoMix**: Seamlessly aligns outgoing and incoming track beats (2/4/8 beats) with temporary timescale tempo stretching and smooth recovery.
- 🎚️ **Equal-Power Crossfade**: Soft-knee limited dual-track overlap mixing that prevents digital clipping.
- 🔇 **Smart Silence Skipping**: Isolated background pre-analysis and real-time PCM silence detection to trim dead air.
- 🔊 **EBU R128 Loudness Normalization**: Industry-standard dual-gated ITU-R BS.1770-4 loudness leveling to -14.0 LUFS.
- 🗣️ **TTS / Announcement Overlays**: Voice prompt playback with background music ducking (sequential or parallel).
- ⚡ **JIT Epoch-Fenced Protocol (v2)**: Race-condition-free queue coordination with idempotency keys.

**No changes to Lavalink's codebase required.** Built on pure Kotlin/JVM with zero native or external library dependencies for analysis.

---

## Documentation & References

- 📋 **OpenAPI REST Specification**: [`api/openapi.yaml`](api/openapi.yaml)
- 🤖 **Bot Integration Guide**: [`examples/lavalink-client/README.md`](examples/lavalink-client/README.md)
- 🏗️ **Architecture Reference**: [`docs/architecture.md`](docs/architecture.md)
- 🛠️ **Build & Testing Guide**: [`docs/development.md`](docs/development.md)
- 📊 **Adaptive Transition Calibration**: [`docs/adaptive-calibration.md`](docs/adaptive-calibration.md)

---

## Quickstart

### 1. Install Plugin

Add the plugin to your Lavalink `application.yml`:

```yaml
lavalink:
  plugins:
    - dependency: "dev.lavalink.mixer:mixer-plugin:0.3.4"
      repository: "https://maven.pkg.github.com/mochilabs/lavalink_mixer"
```

Or manually download `lavalink_mixer-0.3.4.jar` from [Releases](https://github.com/mochilabs/lavalink_mixer/releases) and place it into Lavalink's `plugins/` directory, then restart Lavalink.

### 2. Enable Filter (Required Once Per Guild)

Lavalink filters are player-scoped. Send the Lavalink `filters` operation **before** or during playback:

```json
{
  "op": "filters",
  "guildId": "123456789012345678",
  "pluginFilters": {
    "mixer": {
      "guildId": "123456789012345678"
    }
  }
}
```

> ⚠️ **Important**: Always send `guildId` as a **string**. Discord snowflake IDs exceed JavaScript's 53-bit integer limit.

---

## Configuration (`application.yml`)

Configure defaults under the `mixer:` block in your Lavalink `application.yml` (all values can be dynamically overridden per guild via REST):

```yaml
mixer:
  # Crossfade & Fade-In
  crossfadeEnabled: true
  crossfadeMs: 5000               # Overlap duration in ms (500 .. 30000)
  fadeInMs: 300                   # Initial fade-in when starting from stop (0 = off)

  # Announcement Ducking
  duckLevel: 0.2                  # Main track volume multiplier during announcements (0.0 .. 1.0)
  duckFadeMs: 300                 # Ducking ramp duration in ms
  maxParallelOverlays: 4          # Max simultaneous parallel TTS overlays

  # Volume Normalization (EBU R128 / ITU-R BS.1770-4)
  normalizationEnabled: false     # Requires pre-decoding pass
  targetLufs: -14.0               # Standard target loudness (-30.0 .. -6.0 LUFS)

  # Silence Skipping
  silenceSkipEnabled: false       # Trims leading/trailing dead air
  silenceThresholdDb: -60.0       # Silence cutoff amplitude in dB
  silenceMinSoundMs: 120          # Minimum sound duration to confirm non-silence
  silenceHeadScanMs: 15000        # Pre-analysis window for track start
  silenceTailScanMs: 15000        # Pre-analysis window for track end
  tailConfirmMs: 1000             # Real-time sustained silence before cut (streams)

  # Beat-Matched AutoMix & BPM Analysis
  bpmDetectionEnabled: false      # Enables tempo & beat grid extraction
  bpmScanMs: 30000                # Analysis window at head and tail (5000 .. 60000 ms)
  beatmatchEnabled: false         # Enables tempo stretching & beat grid alignment
  maxTempoChange: 0.06            # Max allowed tempo adjustment ratio (default 6%, max 12%)
  beatmatchMinConfidence: 0.30    # Min confidence threshold to accept a beat grid

  # Background Workers
  analysisThreads: 2              # Parallel track pre-analysis worker threads
```

---

## Features in Detail

### 1. Beat-Matched AutoMix
The AutoMix engine runs an isolated, pure Kotlin analysis pass on incoming and outgoing tracks:
- **Radix-2 Real FFT (N=2048)** with Hann windowing for zero-allocation spectral analysis.
- **3-Band Spectral Flux**:
  - **Low (30–150 Hz)**: Isolates kick drums and sub-bass transients.
  - **Mid (150–2,000 Hz)**: Tracks snares, claps, and instrumental attacks.
  - **High (2,000–10,000 Hz)**: Tracks hi-hats, cymbals, and shakers.
- **Normalized Autocorrelation**: Unbiased tempo candidate estimation (60–200 BPM) with harmonic octave scoring to prevent half/double-tempo errors.
- **Global Phase Optimization**: Fits the entire onset energy pattern across the analysis window to locate exact beat phase ($\phi \in [0, T)$), completely immune to rogue opening clicks, breath sounds, or trailing reverbs.
- **Boundary Silence Awareness**: Automatically trims encoding delays and leading/trailing silence, aligning the beat grids directly across the audible portions of both tracks.
- **Tempo Transition & Recovery**: If tempos differ within `maxTempoChange` (e.g. 124 BPM $\to$ 126 BPM), the secondary track's tempo is stretched using `lavadsp` timescale during the crossfade. Once the outgoing track fades out, the incoming track smoothly returns to its native 1.0 tempo.

### 2. Equal-Power Crossfade with Soft-Knee Limiting
During a crossfade, summing two full-scale audio sources can produce amplitudes up to $+3\text{ dB}$ ($1.41\times$). Rather than hard-clipping into distortion:
- Follows equal-power gain curves: $g_{out}^2 + g_{in}^2 = 1$.
- Any combined signal excursion above `0.9` is compressed smoothly with a soft-knee curve toward `1.0`. A $1.41\times$ peak lands at $\sim 0.98$ (an inaudible $\sim 0.14\text{ dB}$ compression), preserving natural transients without square-wave clipping.

### 3. Smart Silence Skipping
- **Pre-Analysis Pass**: Traces audio forward from `0ms` to detect the true start of audio (skipping MP3/container delays) and backwards from the track end to identify where music terminates.
- **Real-Time Detection**: Continuously monitors PCM peaks in the mixer filter. If a track (or unseekable live stream) goes silent for `tailConfirmMs`, playback advances immediately rather than playing silence.

### 4. EBU R128 Loudness Normalization
- Implements ITU-R BS.1770-4 K-weighting filtering with 400ms momentary windows (75% overlap) and dual-gating ($-70\text{ LUFS}$ absolute, $-10\text{ LU}$ relative).
- Computes integrated loudness and applies linear digital scaling to meet `targetLufs` (default $-14\text{ LUFS}$).
- Clamped to $MAX\_GAIN = 1.0$ by default to prevent digital clipping on quiet tracks.

### 5. TTS / Announcement Overlays
- Play audio notifications, TTS alerts, or voice cues over music without pausing.
- **Ducking**: Automatically attenuates main track volume to `duckLevel` (e.g. 20%) over `duckFadeMs` (e.g. 300ms), plays the announcement, and smoothly restores volume upon completion.
- Supports both `sequential` (queued back-to-back) and `parallel` overlay modes.

---

## Queue Protocol v2 (JIT Prefetch & Epoch Fencing)

To enable seamless crossfades, the server needs the next track *before* the current track finishes. Protocol v2 delegates queue ownership to the server while keeping client control strictly consistent:

```text
[Track A Playing]
        │
        ▼ (Remaining time reaches crossfadeMs + 7000ms)
Server pushes WebSocket: MixerNeedNextTrackEvent
        │
        ▼
Client sends REST: POST /mixer/queue/arm
(Body: { guildId, epoch, encodedTrack }, Header: Idempotency-Key)
        │
        ▼ (Server decodes & analyzes Track B in background)
Server plans Transition (BeatMatched / Crossfade / SilenceTrim)
        │
        ▼ (Playback reaches handoff point)
Server starts secondary player -> MixerFilter mixes Track B into Track A
Server pushes WebSocket: overlapStart
        │
        ▼ (Crossfade completes)
Server promotes Track B to main -> pushes WebSocket: MixerAdvancedEvent
Client syncs its local queue (client keeps autoSkip: false)
```

- **Monotonic Epochs**: Prevents race conditions when arming or skipping. Stale requests with older epochs are rejected with `HTTP 409 Conflict`.
- **Idempotency Keys**: Network retries with the same `Idempotency-Key` and payload return cached success without duplicate actions. Payload mismatches return `HTTP 422`.
- **Client Override**: User commands (`STOP`, `PLAY_NOW`) call `POST /mixer/queue/override` to atomically cancel pending crossfades and execute engine mutations immediately.

---

## REST API Reference

All endpoints accept standard Lavalink `Authorization` headers.

### Queue & Engine Control

| Method | Path | Header | Body | Description |
|---|---|---|---|---|
| `GET` | `/mixer/info` | — | — | Returns `{ protocolVersion: 2, version: "0.3.4" }` |
| `GET` | `/mixer/state?guildId=` | — | — | Returns current state, config, transition status, and BPM |
| `POST` | `/mixer/analyze` | — | `{ guildId, encodedTrack }` | Pre-warms analysis cache before arming (returns 202) |
| `POST` | `/mixer/queue/arm` | `Idempotency-Key` | `{ guildId, epoch, encodedTrack?, isEmpty, preserveTransition? }` | Arms the next track for JIT transition |
| `POST` | `/mixer/queue/invalidate` | `Idempotency-Key` | `{ guildId, epoch }` | Clears armed next track; triggers `MixerNeedNextTrackEvent` |
| `POST` | `/mixer/queue/override` | `Idempotency-Key` | `{ guildId, epoch, action, encodedTrack? }` | Atomic override (`action: "STOP"` or `"PLAY_NOW"`) |
| `POST` | `/mixer/reset` | `Idempotency-Key` | `{ guildId, epoch, windowOpenGraceMs? }` | Resets mixer state machine and synchronizes epochs |

### Transition & DSP Configuration

| Method | Path | Body Parameters | Description |
|---|---|---|---|
| `POST` | `/mixer/crossfade` | `{ guildId, enabled, durationMs? }` | Configure crossfade (500–30000ms) |
| `POST` | `/mixer/fade-in` | `{ guildId, durationMs }` | Configure initial fade-in (0–10000ms) |
| `POST` | `/mixer/bpm` | `{ guildId, enabled, scanMs?, beatmatchEnabled?, maxTempoChange?, minConfidence? }` | Configure BPM detection & AutoMix |
| `POST` | `/mixer/beatmatch` | `{ guildId, enabled, maxTempoChange?, minConfidence? }` | Toggle beat matching independently |
| `POST` | `/mixer/silence` | `{ guildId, enabled, thresholdDb?, minSoundMs?, headScanMs?, tailScanMs?, confirmMs? }` | Configure silence skipping |
| `POST` | `/mixer/normalization` | `{ guildId, enabled, targetLufs? }` | Configure EBU R128 loudness normalization |

### Overlays & Announcements

| Method | Path | Body Parameters | Description |
|---|---|---|---|
| `POST` | `/mixer/announce` | `{ guildId, identifier?, encodedTrack?, duckLevel?, mode? }` | Queue TTS / voice overlay (`mode`: `sequential` \| `parallel`) |
| `POST` | `/mixer/announce/cancel` | `{ guildId }` | Stop active announcement and restore music volume |
| `DELETE` | `/mixer/announce/queue` | Query: `?guildId=` | Clear all queued announcements |

---

## WebSocket Events (`op: "mixer"`)

Events dispatched to connected Lavalink WebSocket clients:

```jsonc
// 1. JIT window fired: client should arm the next track
{ "op": "mixer", "type": "MixerNeedNextTrackEvent", "guildId": "123", "remainingMs": 12000, "currentTrackEncoded": "..." }

// 2. Overlap crossfade started
{ "op": "mixer", "type": "overlapStart", "guildId": "123", "crossfadeMs": 4000, "handoffInMs": 4000, "beatMatched": true, "fadeBeats": 8, "targetBpm": 124.0, "rateRatio": 1.016 }

// 3. Playback advanced server-side: client must sync local queue
{ "op": "mixer", "type": "MixerAdvancedEvent", "guildId": "123", "epoch": 2, "encodedTrack": "...", "handoffType": "CROSSFADE", "handoffId": "uuid" }

// 4. Overlap completed
{ "op": "mixer", "type": "overlapEnd", "guildId": "123", "reason": "handoff", "beatMatched": true }

// 5. User override executed
{ "op": "mixer", "type": "MixerOverriddenEvent", "guildId": "123", "epoch": 3, "requestId": "key", "action": "PLAY_NOW" }
```

---

## Enabling AutoMix via REST

To enable beat-matched transitions on a running Lavalink server:

```http
POST /mixer/crossfade
Content-Type: application/json

{
  "guildId": "123456789012345678",
  "enabled": true,
  "durationMs": 5000
}
```

```http
POST /mixer/bpm
Content-Type: application/json

{
  "guildId": "123456789012345678",
  "enabled": true,
  "beatmatchEnabled": true,
  "scanMs": 30000,
  "maxTempoChange": 0.06,
  "minConfidence": 0.30
}
```

Check the active transition decision anytime:
```http
GET /mixer/state?guildId=123456789012345678
```
Look at `transitionKind` and `decisionReason`:
- `"transitionKind": "beat_matched"` $\to$ Successful beat-matched automix.
- `"transitionKind": "short_crossfade"` $\to$ Equal-power crossfade fallback (e.g. tempo difference exceeded `maxTempoChange` or key clash).

---

## Architecture

Discord voice requires a single continuous Opus stream. `lavalink_mixer` performs PCM mixing **before encoding**:

```text
[Main AudioPlayer]  ──> [Main Decoder] ──> [MixerFilter] ──> [Volume / EQ Filters] ──> [Opus Encoder] ──> [Discord Voice]
                                                ▲
                                                │ (Raw PCM sum: main * mainGain + sub * subGain)
[Sub AudioPlayer]   ──> [Sub Decoder]  ──> [SiphonQueue]
(Secondary / TTS)
```

1. **Siphon Filter**: An isolated secondary player decodes secondary audio into a bounded float PCM ring buffer without binding to Discord voice.
2. **MixerFilter**: Attached at the head of the main player's filter chain, mixing raw PCM chunks directly as they are decoded.
3. **Decoupled Engine**: Track state mutations (`GuildMixer`) run on an event-driven queue, ensuring Lavaplayer `play()`/`stop()` operations never deadlock the realtime audio thread.

---

## Building from Source

Requirements: **JDK 17+**

```bash
# Build JAR
./gradlew jar

# Run all unit and audio integration tests
./gradlew test

# Full build including artifacts
./gradlew build
```

Artifact output: `build/libs/lavalink_mixer-0.3.4.jar`.

---

## License

This project is released into the public domain under the [Unlicense](UNLICENSE).
