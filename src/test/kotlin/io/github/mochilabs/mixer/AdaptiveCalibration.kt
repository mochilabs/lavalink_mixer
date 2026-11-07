package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.system.measureNanoTime

/** Opt-in pair measurements against local WAVs, without playback or network sources. */
object AdaptiveCalibration {
    /** The transition length the plugin defaults to; the report shows what the planner does with it. */
    private const val REQUESTED_CROSSFADE_MS = 5000L

    private data class PairSpec(val outgoing: File, val incoming: File, val preserve: Boolean)
    private data class Measurement(val durationMs: Long, val result: TrackAnalysisResult, val elapsedMs: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        try { run(args) } catch (e: Exception) {
            System.err.println("Adaptive evaluation failed: ${e.message}")
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        require(args.size in 1..3) { "Usage: AdaptiveCalibration <pairs.tsv> [scanMs:5000..60000] [output.tsv]" }
        val manifest = File(args[0]).canonicalFile
        require(manifest.isFile) { "Pair manifest does not exist: $manifest" }
        val scanMs = args.getOrNull(1)?.toLongOrNull() ?: if (args.size < 2) 30000L else error("Invalid scanMs")
        require(scanMs in 5000..60000) { "scanMs must be between 5000 and 60000" }
        val lines = manifest.readLines()
        require(lines.firstOrNull() == "outgoing\tincoming\tpreserve_context") { "Expected header: outgoing<TAB>incoming<TAB>preserve_context" }
        val pairs = lines.drop(1).filter { it.isNotBlank() }.mapIndexed { index, line ->
            val cells = line.split('\t')
            require(cells.size == 3 && cells[2] in listOf("true", "false")) { "Invalid pair at row ${index + 2}" }
            fun source(path: String): File {
                val file = File(path).let { if (it.isAbsolute) it else File(manifest.parentFile, path) }.canonicalFile
                require(file.isFile && file.extension.equals("wav", true)) { "Not a local WAV file: $file" }
                return file
            }
            PairSpec(source(cells[0]), source(cells[1]), cells[2] == "true")
        }
        require(pairs.isNotEmpty()) { "Manifest must contain at least one pair" }
        val output = File(args.getOrNull(2) ?: "build/adaptive-measurements.tsv").canonicalFile
        require(output != manifest && pairs.none { output == it.outgoing || output == it.incoming }) { "Report must not overwrite an input" }
        output.parentFile.mkdirs()
        val manager = DefaultAudioPlayerManager()
        val service = TrackAnalysisService(manager, 0)
        val measurements = mutableMapOf<File, Measurement>()
        var failures = 0
        try {
            manager.registerSourceManager(LocalAudioSourceManager())
            manager.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
            val settings = TrackAnalysisSettings(false, false, true, -60f, 120, 15000, 15000, scanMs)
            fun measure(file: File): Measurement = measurements.getOrPut(file) {
                val track = load(manager, file)
                require(track.isSeekable && track.duration > 0) { "Track needs a known duration and seeking: ${file.name}" }
                service.beginScan()
                lateinit var result: TrackAnalysisResult
                val elapsed = measureNanoTime { result = service.analyze(track, settings) } / 1_000_000.0
                Measurement(track.duration, result, elapsed)
            }
            output.bufferedWriter().use { writer ->
                val columns = listOf("outgoing", "incoming", "preserve_context", "status", "error", "scan_ms",
                    "out_analysis_ms", "in_analysis_ms", "out_bpm", "in_bpm", "out_grid_confidence", "in_grid_confidence",
                    "out_downbeat_confidence", "in_downbeat_confidence", "out_phrase_confidence", "in_phrase_confidence",
                    "out_key", "in_key", "out_key_confidence", "in_key_confidence", "out_rms", "in_rms",
                    "out_stability", "in_stability", "out_unknown", "in_unknown", "strategy", "reason", "transition_kind",
                    "beat_matched", "target_bpm", "rate_ratio", "fade_beats", "start_ms", "handoff_ms",
                    "incoming_start_ms", "overlap_ms", "recovery_ms")
                writer.appendLine(columns.joinToString("\t"))
                for (pair in pairs) {
                    val row = mutableMapOf<String, Any?>("outgoing" to pair.outgoing.path, "incoming" to pair.incoming.path,
                        "preserve_context" to pair.preserve, "scan_ms" to scanMs)
                    try {
                        val out = measure(pair.outgoing)
                        val inc = measure(pair.incoming)
                        val outFeatures = out.result.featuresTail
                        val inFeatures = inc.result.featuresHead
                        val outGrid = out.result.bpmTail
                        val inGrid = inc.result.bpmHead
                        val incomplete = out.result.aborted || inc.result.aborted ||
                            outFeatures?.unknownReason == "incomplete_window" || inFeatures?.unknownReason == "incomplete_window"
                        if (incomplete) failures++
                        row["status"] = if (incomplete) "incomplete" else "ok"
                        row["out_analysis_ms"] = out.elapsedMs
                        row["in_analysis_ms"] = inc.elapsedMs
                        for ((prefix, features, grid) in listOf(Triple("out", outFeatures, outGrid), Triple("in", inFeatures, inGrid))) {
                            row["${prefix}_bpm"] = grid?.bpm
                            row["${prefix}_grid_confidence"] = grid?.bpmConfidence
                            row["${prefix}_downbeat_confidence"] = grid?.downbeatConfidence
                            row["${prefix}_phrase_confidence"] = grid?.phraseConfidence
                            row["${prefix}_rms"] = features?.rms
                            row["${prefix}_stability"] = features?.rhythmicStability
                            row["${prefix}_unknown"] = features?.unknownReason ?: if (features == null) "missing_features" else ""
                            when (val key = features?.key) {
                                is KeyEstimate.Candidate -> {
                                    row["${prefix}_key"] = "${key.pitchClass}:${key.mode}"
                                    row["${prefix}_key_confidence"] = key.confidence
                                }
                                is KeyEstimate.Unknown -> row["${prefix}_key"] = "unknown:${key.reason}"
                                null -> row["${prefix}_key"] = "unknown:missing_features"
                            }
                        }
                        // No guild or memory: the report shows the planner's own choice, not a varied one.
                        val recipe = AdaptiveTransitionPlanner.plan(
                            outgoingGridTail = outGrid,
                            incomingGridHead = inGrid,
                            outgoingFeaturesTail = outFeatures,
                            incomingFeaturesHead = inFeatures,
                            requestedCrossfadeMs = REQUESTED_CROSSFADE_MS,
                            contentEndMs = out.durationMs,
                            incomingLeadMs = inFeatures?.leadingSilenceMs ?: 0L,
                            incomingDurationMs = inc.durationMs,
                            preserveContext = pair.preserve,
                        )
                        row["strategy"] = recipe.strategy.name
                        row["reason"] = recipe.reason
                        row["transition_kind"] = AdaptiveTransitionPlanner.legacyKind(recipe.strategy)
                        val plan = AdaptiveTransitionPlanner.toCrossfadePlan(recipe, out.durationMs)
                        if (plan == null) {
                            // No overlap: the original boundary is kept.
                            row["transition_kind"] = "preserve"
                            row["beat_matched"] = false
                            row["start_ms"] = out.durationMs; row["handoff_ms"] = out.durationMs; row["incoming_start_ms"] = 0
                        } else {
                            row["beat_matched"] = plan.beatMatched
                            row["target_bpm"] = plan.targetBpm.takeIf { it > 0f }
                            row["rate_ratio"] = plan.rateRatio.takeIf { plan.beatMatched }
                            row["fade_beats"] = plan.fadeBeats.takeIf { it > 0 }
                            row["start_ms"] = plan.startMs; row["handoff_ms"] = plan.handoffMs
                            row["incoming_start_ms"] = plan.incomingStartMs
                            row["overlap_ms"] = plan.overlapMs; row["recovery_ms"] = plan.recoveryMs
                        }
                    } catch (e: Exception) {
                        failures++
                        row["status"] = "error"
                        row["error"] = e.message ?: e.javaClass.simpleName
                    }
                    writer.appendLine(columns.joinToString("\t") { escape(row[it]) })
                }
            }
        } finally {
            service.destroy()
            manager.shutdown()
        }
        println("Wrote ${pairs.size} pair measurements to $output")
        check(failures == 0) { "$failures pair(s) failed or had incomplete scans; inspect the report" }
    }

    private fun escape(value: Any?) = value?.toString()?.replace("\\", "\\\\")?.replace("\t", "\\t")
        ?.replace("\r", "\\r")?.replace("\n", "\\n") ?: ""

    private fun load(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val result = CompletableFuture<AudioTrack>()
        manager.loadItem(file.path, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { result.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { result.completeExceptionally(IllegalArgumentException("Expected a WAV track")) }
            override fun noMatches() { result.completeExceptionally(IllegalArgumentException("Unsupported WAV: ${file.name}")) }
            override fun loadFailed(exception: FriendlyException) { result.completeExceptionally(exception) }
        })
        return result.get(30, TimeUnit.SECONDS)
    }
}
