# Measure BPM confidence on local tracks

Use the opt-in `bpmCalibration` task to collect head and tail measurements from
local WAV files. The runner uses `TrackAnalysisService` and the test runtime
dependencies. It does not start Lavalink, connect to Discord, download audio,
or change the plugin's confidence threshold.

## Prepare the audio

Choose 20–30 tracks you can use locally. Include steady beats, tempo changes,
weak percussion, and ambient passages. Put the WAV files directly in one
directory. The runner ignores other extensions and does not traverse subdirectories.

Keep reference BPM and beat timestamps separately for each file and window.
Reserve some tracks for evaluation after threshold selection. A whole-track
BPM label alone does not establish the timing of beats in the tail window.

## Run the measurements

From the repository root with JDK 17 available, run:

```bash
./gradlew -q bpmCalibration -PaudioDir=/absolute/path/to/wavs
```

The runner writes `build/bpm-measurements.tsv`, replacing any existing report.
Dependency logs stay on the console and do not enter the TSV.

To change the requested scan duration and report path, use `-PscanMs` and `-PoutputFile`:

```bash
./gradlew -q bpmCalibration -PaudioDir=/absolute/path/to/wavs -PscanMs=15000 -PoutputFile=build/bpm-15s.tsv
```

The default is 30000 ms. Accepted values are 5000–60000 ms. The runner follows
the production service's short-track behavior, which can reuse a single
whole-track grid for both windows. `scan_ms` records the requested setting,
not a measured number of decoded samples.

The TSV has one header and two rows per file, labeled `head` and `tail`.
Each row contains the file name, window, track duration, scan setting, BPM,
phase in milliseconds, confidence, status, and error message. `phase_ms` uses
the track timeline, including the tail window offset. It is not a downbeat label.
Tabs, newlines, carriage returns, and backslashes in text cells use backslash escapes.

Check `status` before interpreting measurements. `ok` means a grid was returned,
not that the grid is musically correct. `no_grid` is a completed analysis without
a usable grid. `aborted` and `error` rows are failures; the command exits nonzero
after it finishes the remaining files. A missing directory or an empty WAV
selection also causes a nonzero exit.

## Compare against the reference

Compare measured BPM and beat timing with the reference for each window.
Record incorrect beat-match approvals and correct matches rejected by each
candidate confidence threshold. Check half-tempo and double-tempo errors
explicitly. Do not treat separation between synthetic click tracks and silence
as evidence that a threshold works on real music.

Choose a threshold using the calibration subset, then report its errors on
the reserved evaluation subset. Record the scan duration and plugin revision
with the result. A table of confidence values alone is a measurement report,
not a calibrated threshold. Real-track calibration remains pending until
audio and reference labels are supplied and evaluated.
