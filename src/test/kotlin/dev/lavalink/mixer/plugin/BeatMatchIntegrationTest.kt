package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeatMatchIntegrationTest {
    private val sampleRate = 48000
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    private class RecordingSink {
        private val lock = Any()
        private val chunks = mutableListOf<FloatArray>()

        fun add(chunk: FloatArray) = synchronized(lock) { chunks.add(chunk) }
        val size: Int get() = synchronized(lock) { chunks.sumOf { it.size } }

        fun snapshot(): FloatArray = synchronized(lock) {
            var total = 0
            for (c in chunks) total += c.size
            FloatArray(total).also { out ->
                var i = 0
                for (c in chunks) {
                    c.copyInto(out, i)
                    i += c.size
                }
            }
        }
    }

    private class RecordingFilter(
        private val sink: RecordingSink,
        private val downstream: FloatPcmAudioFilter?,
    ) : FloatPcmAudioFilter {
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            if (length > 0 && input.isNotEmpty()) {
                val copy = FloatArray(length)
                input[0].copyInto(copy, 0, offset, offset + length)
                sink.add(copy)
            }
            downstream?.process(input, offset, length)
        }

        override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Test
    fun `beat-matched crossfade aligns kicks and hands off at tempo-adjusted source position`() {
        val manager = manager()
        val fileA = stereoClickFile("mixer-bm-out", 120.0, 12, offsetMs = 0L)
        val fileB = stereoClickFile("mixer-bm-in", 125.0, 12, offsetMs = 200L)
        try {
            val config = MixerConfig().apply {
                crossfadeEnabled = true
                crossfadeMs = 4500
                fadeInMs = 0
                maskMs = 0
                silenceSkipEnabled = false
                bpmDetectionEnabled = true
                bpmScanMs = 8000
                beatmatchEnabled = true
                maxTempoChange = 0.06f
            }
            val mixer = GuildMixer(101L, manager, config, java.util.concurrent.Executor { it.run() })
            val player = manager.createPlayer()
            val sink = RecordingSink()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf(mixer.newFilter(RecordingFilter(sink, output)))
            })
            mixer.attachMain(iPlayer(101L, player))

            val trackA = loadTrack(manager, fileA)
            val trackB = loadTrack(manager, fileB)
            player.playTrack(trackA)
            mixer.queueNext(CompletableFuture.completedFuture(trackB))
            mixer.analyzeBlocking(trackB)

            var sawOverlap = false
            var overlapStartSample = -1
            var handoffSample = -1
            var wasBeatMatchedDuringOverlap = false
            var speedDuringOverlap = 1.0
            var handoffCompleted = false
            var subOutAtHandoff = 0L
            var subSrcAtHandoff = 0L

            pumpUntil(player, mixer) {
                if (mixer.isXfadeActive()) {
                    if (!sawOverlap) overlapStartSample = sink.size
                    sawOverlap = true
                    if (mixer.isXfadeBeatMatchedForTest()) {
                        wasBeatMatchedDuringOverlap = true
                        speedDuringOverlap = mixer.currentSubEffectiveSpeedForTest()
                    }
                } else if (sawOverlap && !handoffCompleted) {
                    handoffCompleted = true
                    handoffSample = sink.size
                    subOutAtHandoff = mixer.subOutputSamplesForTest()
                    subSrcAtHandoff = mixer.subSourceSamples()
                }
                handoffCompleted || (sink.size * 1000L / sampleRate > 18_000)
            }

            assertTrue(sawOverlap, "crossfade overlap never armed")
            assertTrue(wasBeatMatchedDuringOverlap, "expected beat-matched overlap")
            // 120.0 / 125.0 = 0.96
            assertTrue(abs(speedDuringOverlap - 0.96) < 0.01, "expected speed ~0.96 during overlap, got $speedDuringOverlap")
            assertEquals(1.0, mixer.currentSubEffectiveSpeedForTest(), 1e-4, "speed should reset to 1.0 after handoff")
            assertTrue(handoffCompleted, "handoff never completed")
            assertTrue(subOutAtHandoff > 0L, "sub output samples should be > 0")
            assertTrue(subSrcAtHandoff > 0L, "sub source samples should be > 0")
            assertTrue(
                subSrcAtHandoff < subOutAtHandoff,
                "expected subSourceSamples ($subSrcAtHandoff) < subOutputSamples ($subOutAtHandoff) since r=0.96 < 1.0",
            )
            assertTrue(player.playingTrack !== trackA, "outgoing track was never handed off")

            val recorded = sink.snapshot()
            val overlapEndSample = (overlapStartSample + subOutAtHandoff.toInt()).coerceAtMost(recorded.size)
            assertTrue(overlapStartSample in 0 until overlapEndSample, "invalid overlap sample bounds: $overlapStartSample..$overlapEndSample")
            val overlapSlice = recorded.copyOfRange(overlapStartSample, overlapEndSample)
            val overlapGrid = BpmDetector.analyzeGrid(listOf(arrayOf(overlapSlice)), sampleRate)
            assertTrue(overlapGrid != null && abs(overlapGrid.bpm - 120f) <= 2.5f, "mixed overlap output should maintain ~120 BPM grid, got $overlapGrid")

            if (recorded.size - overlapEndSample >= sampleRate * 3) {
                val postHandoffSlice = recorded.copyOfRange(overlapEndSample, recorded.size)
                val postGrid = BpmDetector.analyzeGrid(listOf(arrayOf(postHandoffSlice)), sampleRate)
                assertTrue(postGrid != null && abs(postGrid.bpm - 125f) <= 2.5f, "post-handoff output should resume incoming native ~125 BPM, got $postGrid")
            }
            mixer.destroy()
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun `tempo difference exceeding maxTempoChange falls back to EqualPower crossfade`() {
        val manager = manager()
        val fileA = stereoClickFile("mixer-eq-out", 120.0, 10, offsetMs = 0L)
        val fileB = stereoClickFile("mixer-eq-in", 145.0, 10, offsetMs = 0L)
        try {
            val config = MixerConfig().apply {
                crossfadeEnabled = true
                crossfadeMs = 2500
                fadeInMs = 0
                maskMs = 0
                silenceSkipEnabled = false
                bpmDetectionEnabled = true
                bpmScanMs = 8000
                beatmatchEnabled = true
                maxTempoChange = 0.06f
            }
            val mixer = GuildMixer(102L, manager, config, java.util.concurrent.Executor { it.run() })
            val player = manager.createPlayer()
            val sink = RecordingSink()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf(mixer.newFilter(RecordingFilter(sink, output)))
            })
            mixer.attachMain(iPlayer(102L, player))

            val trackA = loadTrack(manager, fileA)
            val trackB = loadTrack(manager, fileB)
            player.playTrack(trackA)
            mixer.queueNext(CompletableFuture.completedFuture(trackB))
            mixer.analyzeBlocking(trackB)

            var sawOverlap = false
            var everBeatMatched = false
            var maxSpeedDeviation = 0.0
            var handoffCompleted = false

            pumpUntil(player, mixer) {
                if (mixer.isXfadeActive()) {
                    sawOverlap = true
                    if (mixer.isXfadeBeatMatchedForTest()) {
                        everBeatMatched = true
                    }
                    val speed = mixer.currentSubEffectiveSpeedForTest()
                    val dev = abs(speed - 1.0)
                    if (dev > maxSpeedDeviation) maxSpeedDeviation = dev
                } else if (sawOverlap && !handoffCompleted) {
                    handoffCompleted = true
                }
                handoffCompleted || (sink.size * 1000L / sampleRate > 15_000)
            }

            assertTrue(sawOverlap, "crossfade overlap never ran")
            assertFalse(everBeatMatched, "should not be beat-matched when tempo difference exceeds maxTempoChange")
            assertEquals(0.0, maxSpeedDeviation, 1e-4, "effective speed should stay 1.0")
            assertTrue(handoffCompleted, "handoff never completed")
            assertTrue(player.playingTrack !== trackA, "outgoing track was never handed off")

            mixer.destroy()
        } finally {
            fileA.delete()
            fileB.delete()
        }
    }

    private fun pumpUntil(player: AudioPlayer, mixer: GuildMixer, stop: () -> Boolean) {
        val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.nanoTime()
        var frames = 0L
        while (System.currentTimeMillis() < deadline) {
            if (player.provide(frame)) {
                frames++
                mixer.drain()
                if (frames % 5L == 0L) mixer.poll()
                pace(started, frames)
            } else {
                mixer.poll()
                Thread.sleep(1)
            }
            if (stop()) return
        }
    }

    private fun pace(started: Long, frames: Long) {
        val due = started + frames * 20_000_000L
        val remaining = due - System.nanoTime()
        if (remaining <= 0L) return
        try {
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = format
        return m
    }

    private fun iPlayer(guildId: Long, ap: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAudioPlayer" -> ap
            "getGuildId" -> guildId
            "getTrack" -> ap.playingTrack
            "isPlaying" -> ap.playingTrack != null
            "play" -> {
                ap.playTrack(args!![0] as AudioTrack)
                null
            }
            "stop" -> {
                ap.stopTrack()
                null
            }
            else -> null
        }
    } as IPlayer

    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                future.complete(playlist.selectedTrack ?: playlist.tracks.first())
            }

            override fun noMatches() {
                future.completeExceptionally(IllegalStateException("No match for ${file.absolutePath}"))
            }

            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun stereoClickFile(prefix: String, bpm: Double, seconds: Int, offsetMs: Long = 0L): File {
        val file = File.createTempFile(prefix, ".wav")
        val totalSamples = sampleRate * seconds
        val periodSamples = (sampleRate * 60.0 / bpm).toInt()
        val offsetSamples = (sampleRate * offsetMs / 1000L).toInt()
        val dataLen = totalSamples * 2 * 2 // stereo 16-bit
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1) // PCM
            out.writeShortLE(2) // stereo
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 4) // byte rate
            out.writeShortLE(4) // block align
            out.writeShortLE(16) // bits
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (pos in 0 until totalSamples) {
                val rel = pos - offsetSamples
                val click = rel >= 0 && (rel % periodSamples) < 100
                val v = if (click) 25000 else 0
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
