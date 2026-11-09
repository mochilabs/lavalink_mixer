# Evaluate adaptive transitions on local track pairs

The opt-in `adaptiveCalibration` task decodes local WAV files and runs the same
feature analyzer and transition planner as the plugin. It writes a report;
it does not play music, connect to Discord, download tracks, or change settings.
The reported scores and policy thresholds are **uncalibrated heuristics**.
Synthetic tests establish repeatable behavior, not musical quality on real songs.

## Prepare a pair manifest

Choose audio you can use locally. Include steady percussion, tempo changes,
sparse intros, quiet outros, live recordings, and harmonically distant pairs.
Keep separate reference notes for expected beats, audible cut points, key
uncertainty, and whether the original boundary must be preserved.

Create a UTF-8 TSV with this exact three-column header. Separate cells with
literal tab characters, not spaces:

```text
outgoing	incoming	preserve_context
tracks/outgoing.wav	tracks/incoming.wav	false
tracks/live-part-1.wav	tracks/live-part-2.wav	true
```

Paths can be absolute or relative to the manifest directory. Every path must
name an existing WAV file. `preserve_context` must be `true` or `false`.
Preservation is an explicit choice; the runner does not infer album continuity
or live-recording boundaries from file names. Filenames containing tab or newline
characters cannot be represented in this manifest.

## Run the report

From the repository root with JDK 17 available:

```bash
./gradlew -q adaptiveCalibration -PpairsFile=/absolute/path/to/pairs.tsv
```

The default report is `build/adaptive-measurements.tsv`, replaced on each run.
The runner refuses to overwrite its manifest or input WAVs. To choose a scan
setting and output path:

```bash
./gradlew -q adaptiveCalibration -PpairsFile=/absolute/path/to/pairs.tsv -PscanMs=15000 -PoutputFile=build/adaptive-15s.tsv
```

`scanMs` accepts 5000–60000 ms and defaults to 30000 ms. The evaluation requests
a 5000 ms transition with the plugin's default tempo limit and beat confidence.
A unique WAV is analyzed once per invocation; repeated pairs reuse that result.
The `out_analysis_ms` and `in_analysis_ms` fields repeat the original elapsed
analysis time, not an additional cost for each pair. These wall-clock values
include analysis decoding and feature extraction, but exclude track loading.
They are diagnostic measurements, not a throughput benchmark.

## Interpret the measurements

Each row records the selected plan and reason, selected outgoing endpoint and
incoming start, overlap and recovery durations, local BPM and grid confidence,
key candidate and confidence, RMS energy, rhythmic stability, and unknown reasons.
Outgoing measurements use the tail window; incoming measurements use the head.
Source timestamps use milliseconds. Key pitch classes use 0=C through 11=B,
followed by MAJOR or MINOR. Unknown keys include a reason instead of a guessed key.

`status=ok` means the analysis completed, not that the result is musically correct.
`incomplete` means a scan aborted or did not cover its requested window; `error`
records a decode or input failure. Failed pairs remain in the report, and the
command exits nonzero after processing the other pairs. Unknown rhythm or key
on a successfully decoded track is a valid measurement, not a decoder failure.
Text report cells escape backslashes, tabs, carriage returns, and newlines.

The report's `strategy` column is one of the planner's seven strategies, with
the wire label in `transition_kind`:

- **PHRASE_MATCHED_BASS_SWAP** and **SHORT_BASS_SWAP** (`beat_matched`): compatible
  tempos with confident downbeats (and phrases for the first). The bass is swapped
  at the fade midpoint.
- **BEATMATCHED_BLEND** (`beat_matched`): compatible tempos, but bar boundaries are
  uncertain or the meter is not 4/4. A 2-, 4-, or 8-beat phase-snapped fade.
- **DOWNBEAT_CUT** (`crossfade`): tempos too far apart, both downbeats confident,
  both sides energetic. A 10 ms cut on a bar line.
- **ECHO_OUT** (`crossfade`): tempos too far apart otherwise. A delayed echo of the
  outgoing track, up to two seconds.
- **DEGRADED_FILTER_SWEEP** (`crossfade`): no usable grid, but valid features.
- **ABSOLUTE_FALLBACK** (`short_crossfade`): no usable metadata, or no 2-, 4-, 8-, 16-,
  or 32-beat fade fits the requested length (`fade_window_too_short`). It keeps the
  requested length, bounded by the outgoing content.

A pair marked `preserve_context=true` reports `reason=preserve_context` and
`transition_kind=preserve`. `start_ms` and `handoff_ms` then equal the outgoing
duration, and no overlap columns are filled.

Downbeat and phrase confidence are in `out_/in_downbeat_confidence` and
`out_/in_phrase_confidence`. When only one end of a track had a measurable pulse,
the other end carries a projected tempo with both confidences at 0, which keeps it
out of the bass-swap and downbeat-cut strategies. Check these columns before
trusting a strategy that depends on them.

The runner calls the planner without a guild or transition memory, so the report
shows the planner's own choice and not a varied one. It requests a 5000 ms
transition and uses the full file duration as the outgoing content end, because
silence analysis is off.

The feature analyzer uses 20 ms energy bins and at most 48 Hann-windowed Goertzel
chroma measurements over C3–B5. It has no vocal, verse, or chorus recognition.
Downbeat and phrase detection are heuristics over the same local windows.

Current gates include RMS below 0.008, energy ratios above 3.98 (about 12 dB),
rhythmic stability below 0.35, and key confidence at least 0.6 before rejecting a
key clash. The tempo deadband is derived from `maxTempoChange` and the grids'
confidence. Unknown keys do not establish compatibility and do not alone prevent
rhythm matching. These values have not been fitted or validated on a real corpus.

## Review and tune

Use one subset of pairs to inspect false beat approvals, wrong bass-swap or
downbeat-cut choices, unnecessary short fades, and cuts into audible material. Review ambiguous keys separately
from confident incorrect keys. Record reference beat offsets and half/double-tempo
mistakes for both local windows. Include the plugin revision, scan setting, and
reference notes with every report.

Reserve a separate subset for evaluation after choosing thresholds. Compare
planner decisions and timestamps with those labels, then listen through a real
Lavalink/Discord connection before claiming perceptual improvement. This runner
does not render transitions or verify live handoff audio. A synthetic fixture
passing this task is a smoke check only; real-track calibration remains pending
until audio and reference judgments are supplied and evaluated.
