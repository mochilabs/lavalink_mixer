package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Opt-in local measurements. This entry point is not a JUnit test. */
object BpmCalibration {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (e: Exception) {
            System.err.println("BPM calibration failed: ${e.message}")
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        require(args.size in 1..3) { "Usage: BpmCalibration <local WAV directory> [scanMs: 5000..60000] [output TSV file]" }
        val directory = File(args[0]).canonicalFile
        require(directory.isDirectory) { "Not a directory: $directory" }
        val scanMs = if (args.size >= 2) args[1].toLongOrNull() else 30_000L
        require(scanMs != null && scanMs in 5_000L..60_000L) { "scanMs must be an integer from 5000 to 60000" }
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: error("Cannot read directory: $directory")
        require(files.isNotEmpty()) { "No WAV files directly inside: $directory" }
        val outputFile = File(args.getOrNull(2) ?: "build/bpm-measurements.tsv").canonicalFile
        require(files.none { it.canonicalFile == outputFile }) { "Output file must not overwrite an input WAV: $outputFile" }
        outputFile.parentFile.mkdirs()
        require(outputFile.parentFile.isDirectory) { "Cannot create output directory: ${outputFile.parentFile}" }

        val manager = DefaultAudioPlayerManager()
        val service = TrackAnalysisService(manager, guildId = 0L)
        var failures = 0
        try {
            manager.registerSourceManager(LocalAudioSourceManager())
            manager.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
            val settings = TrackAnalysisSettings(
                silence = false,
                normalization = false,
                bpm = true,
                silenceThresholdDb = -60f,
                silenceMinSoundMs = 120L,
                silenceHeadScanMs = 15_000L,
                silenceTailScanMs = 15_000L,
                bpmScanMs = scanMs,
            )
            outputFile.bufferedWriter().use { writer ->
                writer.appendLine("file\twindow\tduration_ms\tscan_ms\tbpm\tphase_ms\tconfidence\tstatus\terror")
                for (file in files) {
                    try {
                        val track = load(manager, file)
                        require(track.isSeekable && track.duration > 0L) { "Track must have known duration and support seeking" }
                        service.beginScan()
                        val result = service.analyze(track, settings)
                        if (result.aborted) failures++
                        for ((window, grid) in listOf("head" to result.bpmHead, "tail" to result.bpmTail)) {
                            val status = when {
                                result.aborted -> "aborted"
                                grid == null -> "no_grid"
                                else -> "ok"
                            }
                            row(writer, file.name, window, track.duration, scanMs, grid, status)
                        }
                    } catch (e: Exception) {
                        failures++
                        for (window in listOf("head", "tail")) {
                            row(writer, file.name, window, null, scanMs, null, "error", e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            }
        } finally {
            service.destroy()
            manager.shutdown()
        }
        println("Wrote ${files.size * 2} measurement rows to $outputFile")
        check(failures == 0) { "$failures file(s) failed or aborted; inspect the status and error columns" }
    }

    private fun row(
        writer: BufferedWriter,
        file: String,
        window: String,
        durationMs: Long?,
        scanMs: Long,
        grid: BeatGrid?,
        status: String,
        error: String = "",
    ) {
        writer.appendLine(listOf(file, window, durationMs, scanMs, grid?.bpm, grid?.phaseMs, grid?.confidence, status, error)
            .joinToString("\t") { value ->
                value?.toString()?.replace("\\", "\\\\")?.replace("\t", "\\t")
                    ?.replace("\r", "\\r")?.replace("\n", "\\n") ?: ""
            })
    }

    private fun load(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val loaded = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { loaded.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) {
                loaded.completeExceptionally(IllegalArgumentException("Expected a local audio track, got a playlist"))
            }
            override fun noMatches() {
                loaded.completeExceptionally(IllegalArgumentException("No supported audio track in ${file.name}"))
            }
            override fun loadFailed(exception: FriendlyException) { loaded.completeExceptionally(exception) }
        })
        return loaded.get(30, TimeUnit.SECONDS)
    }
}
