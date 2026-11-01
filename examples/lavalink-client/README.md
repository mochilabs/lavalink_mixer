# mixer-plugin × lavalink-client (Tomato6966) — bot wiring guide

The current server uses mixer protocol v2. The bundled `mixerPlugin.ts`
helper still uses the removed v1 `/mixer/queue/next` endpoints and does not
handle v2 JIT events or epochs. Do not use its queue and skip methods with
the current plugin until the helper is migrated.

The setup snippets below preserve the legacy helper API for reference.
The [protocol-v2 event flow](#protocol-v2-event-flow) and AutoMix REST examples
describe the current server contract. See the [REST specification](../../api/openapi.yaml)
for request bodies and error handling.

## What you need

| Piece | Version | Why |
|---|---|---|
| Lavalink server | v4 | the audio node |
| `mixer-*.jar` | latest build | the plugin (gapless/crossfade/silence/overlay) |
| lavalink-client | v2.9+ | bot-side manager (`filterManager.data` passthrough, manager events, `player.skip()`) |
| discord.js | v14 (or any lib that can forward raw gateway events) | shard transport |

## Step 1 — Server: install the plugin

1. Copy `mixer-*.jar` into the Lavalink server's `plugins/` directory and
   restart. Verify with `GET /version` — the plugin must appear in
   `plugins`.
2. Optional `application.yml` defaults under `mixer:` (all overridable per
   guild via the wiring in step 4):

   ```yaml
   mixer:
     crossfadeEnabled: true
     crossfadeMs: 5000
     fadeInMs: 300
     duckLevel: 0.2
     duckFadeMs: 300
     maskMs: 80
     silenceSkipEnabled: false
     silenceThresholdDb: -60
     silenceMinSoundMs: 120
     silenceHeadScanMs: 15000
     silenceTailScanMs: 15000
     tailConfirmMs: 1000
     analysisThreads: 2
   ```

No other server config needed. The mixer filter is enabled per guild from
the bot (step 4 does it automatically).

## Step 2 — Bot: install lavalink-client

```bash
npm install lavalink-client
```

## Step 3 — Wire the LavalinkManager

`autoSkip: false` is **REQUIRED**. The server starts the preloaded track
itself; with `autoSkip: true` the client races the server's auto-advance on
every trackEnd (double-skips). `mixerPlugin.ts` forces it too.

```ts
import { Client, GatewayIntentBits } from "discord.js";
import { LavalinkManager } from "lavalink-client";
import { attachMixer } from "./mixerPlugin";

const client = new Client({
  intents: [
    GatewayIntentBits.Guilds,
    GatewayIntentBits.GuildVoiceStates,
  ],
});

const lavalink = new LavalinkManager({
  nodes: [
    { authorization: "youshallnotpass", host: "127.0.0.1", port: 2333, id: "main" },
  ],
  // Forward gateway payloads (VOICE_STATE_UPDATE) to the right shard:
  sendToShard: (guildId, payload) =>
    client.guilds.cache.get(guildId)?.shard?.send(payload),
  autoSkip: false, // REQUIRED: the server advances tracks itself
  client: { id: process.env.CLIENT_ID!, username: "MyBot" },
});

// raw gateway events -> lavalink-client
client.on("raw", (d) => lavalink.sendRawData(d));

client.once("ready", () => lavalink.init(client.user));

// Step 4 — attach the mixer integration:
const mixer = attachMixer(lavalink, {
  crossfade: { enabled: true, durationMs: 5000 },
  fadeInMs: 300,
  // silence: { enabled: true, thresholdDb: -60 }, // opt-in: one extra decode per track
});

client.login(process.env.TOKEN);
```

## Step 5 — Play something

### Optional: enable beat-matched AutoMix

Before arming tracks for a guild, enable crossfade, BPM detection, and beat
matching. The mixer filter must also be active for that guild.
Both BPM detection and beat matching default to disabled.

Send the following JSON bodies to the player's Lavalink node with its normal
`Authorization` header and `Content-Type: application/json`. Replace `"123"`
with the guild ID as a string:

```http
POST /mixer/crossfade
{"guildId":"123","enabled":true,"durationMs":5000}

POST /mixer/bpm
{"guildId":"123","enabled":true,"beatmatchEnabled":true,"scanMs":30000,"maxTempoChange":0.06,"minConfidence":0.30}
```

These are plugin REST requests, not additional `attachMixer` options.
They do not need an `Idempotency-Key`. Reapply per-guild settings when the
server creates a new mixer after player destruction, unless server defaults
already enable them.

`POST /mixer/beatmatch` changes only beat-matching settings. It does not enable
BPM analysis. Confirm `crossfadeEnabled`, `bpmDetectionEnabled`, and
`beatmatchEnabled` through `mixer.state(player)` or `GET /mixer/state`.
Keep `autoSkip: false`; the plugin still owns advancement.

Listen for `overlapStart` and `overlapEnd` on the raw-message hook.
`beatMatched: false` with `bpm_disabled` means analysis is off. Missing grids,
low confidence, or incompatible tempos also fall back to ordinary crossfade
when available. Configure the feature before playback and allow analysis time;
the server does not hold playback until a beat grid is ready.

### Start playback

First track: play normally — `queue.add()` + `player.play()`. The
legacy `attachMixer` handler enables the mixer filter but then calls the
removed v1 preload endpoint. A protocol-v2 adapter must instead respond to
`MixerNeedNextTrackEvent` as described below. Do not independently send
`play` on `trackEnd` when the server owns the transition.

```ts
client.on("interactionCreate", async (i) => {
  if (!i.isChatInputCommand()) return;

  if (i.commandName === "play") {
    if (!i.member.voice.channelId) return i.reply("Join a voice channel first");

    const player = lavalink.createPlayer({
      guildId: i.guildId,
      voiceChannelId: i.member.voice.channelId,
    });
    await player.connect(); // first time only; safe to call again

    const res = await lavalink.search(
      { query: i.options.getString("query", true), source: "youtube" },
      i.user,
    );
    if (!res.tracks.length) return i.reply("No results");

    await player.queue.add(res.tracks[0]);
    if (!player.queue.current) await player.play(); // starts the FIRST track
    return i.reply(`Playing **${res.tracks[0].info.title}**`);
  }

  if (i.commandName === "skip") {
    const player = lavalink.players.get(i.guildId);
    if (!player) return i.reply("Nothing playing");
    await mixer.skip(player); // NOT player.skip() — see "User skip" below
    return i.reply("Skipped");
  }

  if (i.commandName === "announce") {
    const player = lavalink.players.get(i.guildId);
    if (!player) return i.reply("Nothing playing");
    try {
      // TTS clip hosted somewhere the Lavalink server can reach:
      await mixer.announce(player, {
        identifier: "https://tts.example.com/clip.mp3",
      });
      return i.reply("Announcing");
    } catch (e: any) {
      if (e?.status === 409) return i.reply("Mixer busy — try again in a second");
      throw e;
    }
  }
});
```

## Protocol-v2 event flow

```
Enable the mixer filter and start A
MixerNeedNextTrackEvent → POST /mixer/queue/arm for B with epoch and Idempotency-Key
Server advances to B → MixerAdvancedEvent → synchronize the client queue
MixerNeedNextTrackEvent for B → arm C
```

The JIT request normally arrives when `crossfadeMs + 7000` ms remain.
Respond with `isEmpty: true` if no next track exists. On a queue change,
send `/mixer/queue/invalidate` with a newer epoch. On a skip or stop,
send `/mixer/queue/override` with `PLAY_NOW` or `STOP`.
Each control request needs an `Idempotency-Key`; reuse a key only for retries
of the same operation. Follow reset and conflict handling in the REST specification.

The server can use analyzed trailing silence to advance before file end.
Beat matching can place the handoff on an earlier beat. Keep the client queue
in sync with `MixerAdvancedEvent` and retain `autoSkip: false`.

## Analysis and playback readiness

`POST /mixer/queue/arm` accepts the next track and schedules analysis.
`ARMED` does not mean analysis has finished. Check `GET /mixer/state`:

- `hasNext: true` means a next-track slot exists.
- `nextTrimMs` reports cached trim points when available.
- `nextTrackBpmConfidence` and `nextTrackPhaseMs` report available BPM analysis.

Analysis uses a dedicated player, separate from the live crossfade and
announcement player. Reply promptly to the JIT request to give analysis time.
If measurements are unavailable at the transition, playback proceeds using
the available trim and ordinary transition fallback. It does not wait for BPM analysis.

First-track caveat: the track you start with plain `player.play()` never
went through the preload slot, so its own leading silence is only skipped
if its trim was already cached from an earlier preload. Every
server-advanced track after that gets lead-trimmed automatically.

## User skip in protocol v2

Use `/mixer/queue/override` with a newer epoch and an `Idempotency-Key`.
For `PLAY_NOW`, include the selected track's `encodedTrack`. The server
clears its armed slot and replaces playback. Use `STOP` when stopping playback.
The legacy `mixer.skip()` method below must be migrated before use with v2.

## Legacy helper API

| Call | Effect |
|---|---|
| `mixer.skip(player)` | Clear the server slot, then `player.skip()` |
| `mixer.announce(player, { identifier? , encodedTrack? }, duckLevel?)` | Overlay audio (TTS/jingle) over ducked music; throws with `status: 409` when busy |
| `mixer.cancelAnnounce(player)` | Stop the overlay, restore music |
| `mixer.state(player)` | Mixer state + config + `nextTrimMs` |
| `mixer.preloadUpcoming(player)` | Force (re)preload of the upcoming client-queue track |

`identifier` is anything the **server** can load (URL, track identifier,
search prefix). Prefer `encodedTrack` (the exact base64 you already have) —
no re-resolution.

## Legacy helper assumptions

1. **`autoSkip: false`** — the server advances tracks itself.
2. **Never send `play` on trackEnd** for server-advanced tracks — the
   wiring's `trackStart`/watchdog handles the rare failure cases.
3. **`resetFilters()` wipes the mixer key** — call
   `enableMixerFilter(player)` again afterwards (it re-puts
   `pluginFilters.mixer` and applies).
4. **Every filters op replaces the whole server-side chain** — never send a
   hand-built filters object without the mixer block; prefer the
   filterManager (`setEQ()` is a partial update, safe anytime).
5. **Repeat modes** (`player.repeatMode !== "off"`): preloading is skipped —
   the repeater owns transitions and the plugin would fight it.
   Gapless/crossfade/silence trim apply to linear queues.
6. **guildIds are always strings** — snowflakes exceed 2^53.

## Troubleshooting

- **TrackStuck events**: inspect server logs and `/mixer/state` before
  issuing a recovery command. A protocol-v2 adapter must account for any
  server-driven advance before it overrides playback. The legacy watchdog
  is not a v2 recovery implementation.
- **Announcement timing**: sequential announcements share the live secondary
  player with crossfades. Analysis uses a separate player; `nextTrimMs` does
  not indicate whether the live secondary player is free.
- **Arming failed**: inspect the HTTP response, epoch, and reset window.
  The legacy helper's `/mixer/queue/next` calls cannot arm a v2 track.
- **No audible effect at all** — the mixer filter must be in the chain:
  check `player.filterManager.data.pluginFilters.mixer` exists (the wiring
  enables it on `playerCreate`/`trackStart`).

## Debugging

```ts
console.log(await mixer.state(player));
// { hasNext, nextTrimMs: [leadMs, trailMs] | null, overlayActive,
//   crossfadeActive, crossfadeEnabled, crossfadeMs, fadeInMs, duckLevel, ... }
```

`nextTrimMs` appears once silence analysis of the queued track finishes
(see "Analysis and playback readiness" above). `hasNext` is true from queue time, even while
`nextTrimMs` is still null.

Server-side REST reference: [`api/openapi.yaml`](../../api/openapi.yaml) ·
plugin internals: [`README.md`](../../README.md)


### Adaptive AutoMix and current BPM

On servers reporting `adaptiveTransitionsSupported: true`, optionally call
`POST /mixer/analyze` with `{guildId, encodedTrack}` when the next queue candidate
changes. Do not wait for it before sending JIT arm/invalidate commands. A busy or
cached warmup returns `accepted:false`; the normal arm path still handles analysis.

Use `currentTrackBpm` only when `currentTrackBpmIdentifier` equals the bot's
confirmed now-playing identifier. Missing fields, null values, and older servers
mean no BPM display. Do not label `nextTrackBpm` as the current track's tempo.

`preserveTransition:true` on an arm expresses explicit continuous-playback intent.
It belongs to that arm's epoch/track and idempotency fingerprint. It suppresses
crossfade, boundary trim and incoming fade-in; omit it for normal AutoMix.
AutoMix is not an album classifier: a client without reliable album provenance
must not guess this flag. Transition events report `transitionKind` and
`decisionReason`; overlap start also reports `overlapMs` and `recoveryMs`.
