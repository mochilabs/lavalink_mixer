package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Measures the PCM delivered by provide(), including the actual handoff boundary. */
class AdaptiveRecoveryIntegrationTest {
    private val sampleRate = 48_000
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    @Test
    fun `recovery silences outgoing audio and returns incoming tempo before handoff`() = verifyOutput(108f, 8, 230L)

    private fun verifyOutput(incomingBpm: Float, fadeBeats: Int, incomingPhaseMs: Long) {
        val outgoingBpm = 120f
        val outgoingSeconds = 14
        val fileA = clickFile(outgoingBpm, 0L, outgoingSeconds, 0)
        val fileB = clickFile(incomingBpm, incomingPhaseMs, 12, 1)
        val manager = DefaultAudioPlayerManager().apply {
            registerSourceManager(LocalAudioSourceManager())
            configuration.outputFormat = format
        }
        val config = MixerConfig().apply {
            crossfadeMs = 6500L
            crossfadeEnabled = true
            fadeInMs = 0
            maskMs = 0
            bpmDetectionEnabled = true
            beatmatchEnabled = true
            maxTempoChange = 0.12f
        }
        // Known source grids keep this test independent of the BPM estimator.
        val mixer = GuildMixer(900L + fadeBeats, manager, config, Executor { })
        val player = manager.createPlayer()
        try {
            player.setFilterFactory(PcmFilterFactory { _, _, downstream -> mutableListOf(mixer.newFilter(downstream)) })
            mixer.attachMain(iPlayer(mixer.guildId, player))
            val outgoing = load(manager, fileA)
            val incoming = load(manager, fileB)
            cacheGrid(outgoing, BeatGrid(outgoingBpm, 0L, 1f))
            cacheGrid(incoming, BeatGrid(incomingBpm, incomingPhaseMs, 1f))
            player.playTrack(outgoing)
            mixer.queueNext(CompletableFuture.completedFuture(incoming))

            val left = ArrayList<Float>()
            val right = ArrayList<Float>()
            val frame = MutableAudioFrame(ByteBuffer.allocate(format.maximumChunkSize()))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            val started = System.nanoTime()
            var frames = 0L
            var sawBeatMatched = false
            var handoffOutputSample = -1
            while (System.nanoTime() < deadline) {
                if (handoffOutputSample < 0 && player.playingTrack != null && player.playingTrack !== outgoing) {
                    handoffOutputSample = left.size
                }
                if (player.provide(frame)) {
                    val pcm = ByteBuffer.wrap(frame.data).order(ByteOrder.LITTLE_ENDIAN)
                    while (pcm.remaining() >= 4) {
                        left.add(pcm.short / 32768f)
                        right.add(pcm.short / 32768f)
                    }
                    frames++
                    mixer.drain()
                    if (frames % 5L == 0L) mixer.poll()
                    sawBeatMatched = sawBeatMatched || mixer.isXfadeBeatMatchedForTest()
                    if (handoffOutputSample < 0 && player.playingTrack != null && player.playingTrack !== outgoing) {
                        handoffOutputSample = left.size
                    }
                    if (handoffOutputSample >= 0 && left.size - handoffOutputSample >= sampleRate * 3) break
                    val remaining = started + frames * 20_000_000L - System.nanoTime()
                    if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                } else {
                    mixer.poll()
                    Thread.sleep(1)
                }
            }
            assertTrue(sawBeatMatched, "expected a beat-matched transition for $incomingBpm BPM")
            assertTrue(handoffOutputSample >= 0, "handoff did not complete")
            assertTrue(right.size - handoffOutputSample >= sampleRate * 3, "required three seconds after handoff were not captured")

            val decision = BeatMatchPolicy.decide(
                BeatGrid(outgoingBpm, 0L, 1f), BeatGrid(incomingBpm, incomingPhaseMs, 1f),
                outgoingSeconds * 1000L, config.crossfadeMs, maxTempoChange = config.maxTempoChange,
            ) as TransitionDecision.BeatMatched
            assertTrue(decision.recoveryMs >= 1500L, "test requires enough recovery to measure pulse intervals")
            val recoveryStart = ((decision.startMs + decision.fadeMs) * sampleRate / 1000).toInt()
            val plannedHandoff = (decision.handoffMs * sampleRate / 1000).toInt()
            val silentStart = recoveryStart + sampleRate / 10
            val silentEnd = minOf(plannedHandoff - sampleRate / 10, handoffOutputSample)
            assertTrue(silentEnd > silentStart)
            assertTrue(left.subList(silentStart, silentEnd).all { abs(it) < 0.001f },
                "outgoing channel must be silent during incoming tempo recovery")

            val incomingOnsets = onsets(right)
            val held = incomingOnsets.filter {
                it >= (decision.startMs * sampleRate / 1000).toInt() + sampleRate / 2 && it < recoveryStart
            }
            assertTrue(held.size >= 3, "not enough overlap pulses")
            held.zipWithNext().forEach { (first, next) ->
                assertTrue(abs(ms(next - first) - 500.0) <= 20.0, "hold interval ${ms(next - first)}ms")
            }
            val preHandoff = incomingOnsets.filter { it < minOf(plannedHandoff, handoffOutputSample) }
            assertTrue(preHandoff.size >= 2)
            val lastInterval = ms(preHandoff.last() - preHandoff[preHandoff.lastIndex - 1])
            val nativePeriodMs = 60_000.0 / incomingBpm
            assertTrue(abs(lastInterval - nativePeriodMs) <= 20.0,
                "tempo must recover before handoff: last interval=$lastInterval, native=$nativePeriodMs")
            assertTrue(lastInterval > 530.0, "incoming remained locked to outgoing500ms beat until handoff")

            // Carrier frequency is independent of the amplitude pulses used to measure tempo.
            val pitchStart = recoveryStart + sampleRate / 4
            val pitchEnd = pitchStart + sampleRate
            val crossings = (pitchStart + 1 until pitchEnd).count { right[it - 1] <= 0 && right[it] > 0 }
            assertTrue(crossings in 435..445, "recovery shifted pitch: $crossings Hz")

            val postOnsets = incomingOnsets.filter { it >= handoffOutputSample }
            assertTrue(postOnsets.size >= 4, "not enough native playback pulses")
            postOnsets.zipWithNext().forEach { (first, next) ->
                assertTrue(abs(ms(next - first) - nativePeriodMs) <= 20.0)
            }
            val boundaryInterval = ms(postOnsets.first() - preHandoff.last())
            assertTrue(abs(boundaryInterval - nativePeriodMs) <= 40.0,
                "handoff repeated or skipped source audio: boundary=$boundaryInterval, native=$nativePeriodMs")
        } finally {
            mixer.destroy()
            player.destroy()
            manager.shutdown()
            fileA.delete()
            fileB.delete()
        }
    }

    private fun onsets(samples: List<Float>): List<Int> {
        val result = mutableListOf<Int>()
        var last = -sampleRate
        for (i in samples.indices) {
            if (abs(samples[i]) > 0.12f && i - last > sampleRate / 5) {
                result.add(i)
                last = i
            }
        }
        return result
    }

    private fun ms(samples: Int): Double = samples * 1000.0 / sampleRate

    private fun cacheGrid(track: AudioTrack, grid: BeatGrid) {
        AnalysisCaches.bpm[track.identifier] = BpmDetector.BpmResult(grid.bpm, grid.phaseMs, grid.confidence)
        AnalysisCaches.bpmHead[track.identifier] = grid
        AnalysisCaches.bpmTail[track.identifier] = grid
        val features = TransitionFeatures(0, track.duration, emptyList(), 0.2f, 1f,
            KeyEstimate.Unknown("synthetic_fixture"), emptyList())
        AnalysisCaches.featuresHead[track.identifier] = features
        AnalysisCaches.featuresTail[track.identifier] = features
    }

    private fun iPlayer(guildId: Long, player: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(IPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getGuildId" -> guildId
            "getAudioPlayer" -> player
            "getTrack" -> player.playingTrack
            "isPlaying" -> player.playingTrack != null
            "play" -> { player.playTrack(args!![0] as AudioTrack); null }
            "stop" -> { player.stopTrack(); null }
            else -> null
        }
    } as IPlayer

    private fun load(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.completeExceptionally(IllegalStateException("unexpected playlist")) }
            override fun noMatches() { future.completeExceptionally(IllegalStateException("missing WAV")) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun clickFile(bpm: Float, phaseMs: Long, seconds: Int, channel: Int): File {
        val file = File.createTempFile("beat-output-", ".wav")
        val count = seconds * sampleRate
        val period = sampleRate * 60.0 / bpm
        val phase = phaseMs * sampleRate / 1000L
        val dataBytes = count * 4
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun intLE(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun shortLE(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            out.writeBytes("RIFF"); intLE(36 + dataBytes); out.writeBytes("WAVEfmt ")
            intLE(16); shortLE(1); shortLE(2); intLE(sampleRate); intLE(sampleRate * 4)
            shortLE(4); shortLE(16); out.writeBytes("data"); intLE(dataBytes)
            for (i in 0 until count) {
                val relative = i - phase
                val beat = if (relative >= 0) (relative / period).toInt() else -1
                val onset = if (beat >= 0) phase + (beat * period).roundToInt() else Long.MAX_VALUE
                val carrier = kotlin.math.sin(2 * Math.PI * 440 * i / sampleRate)
                val amplitude = if (channel == 0) 0.2 else if (i - onset in 0L until 4800L) 0.5 else 0.04
                val value = (carrier * amplitude * 32767).roundToInt()
                shortLE(if (channel == 0) value else 0)
                shortLE(if (channel == 1) value else 0)
            }
        }
        return file
    }
}
