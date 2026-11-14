package io.github.mochilabs.mixer

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Measures the PCM delivered by provide(), including the actual handoff boundary. */
class BeatMatchOutputTest {
    private val sampleRate = 48_000
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    @Test
    fun `two beat unity transition aligns separate source clicks`() = verifyOutput(120f, 2, 130L)

    @Test
    fun `four beat accelerated transition aligns separate source clicks`() = verifyOutput(115f, 4, 230L)

    @Test
    fun `eight beat slowed transition aligns separate source clicks`() = verifyOutput(125f, 8, 310L)

    private fun verifyOutput(incomingBpm: Float, fadeBeats: Int, incomingPhaseMs: Long) {
        val outgoingBpm = 120f
        val outgoingSeconds = 12
        val fileA = clickFile(outgoingBpm, 0L, outgoingSeconds, 0)
        val fileB = clickFile(incomingBpm, incomingPhaseMs, 12, 1)
        val manager = DefaultAudioPlayerManager().apply {
            registerSourceManager(LocalAudioSourceManager())
            configuration.outputFormat = format
        }
        val config = MixerConfig().apply {
            // Unity needs no recovery; otherwise reserve the 4-beat minimum recovery.
            crossfadeMs = fadeBeats * 500L + if (incomingBpm == outgoingBpm) 0L else 2000L
            crossfadeEnabled = true
            fadeInMs = 0
            maskMs = 0
            bpmDetectionEnabled = true
            beatmatchEnabled = true
            maxTempoChange = 0.06f
        }
        // Known source grids keep this test independent of the BPM estimator.
        val mixer = GuildMixer(900L + fadeBeats, manager, config, Executor { })
        val player = manager.createPlayer()
        val logs = TransitionLogCapture(mixer.guildId)
        try {
            player.setFilterFactory(PcmFilterFactory { _, _, downstream -> mutableListOf(mixer.newFilter(downstream)) })
            mixer.attachMain(iPlayer(mixer.guildId, player))
            val outgoing = load(manager, fileA)
            val incoming = load(manager, fileB)
            cacheGrid(outgoing, ExtendedBeatGrid(outgoingBpm, 0L, 1f))
            cacheGrid(incoming, ExtendedBeatGrid(incomingBpm, incomingPhaseMs * 48L, 1f))
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

            val outgoingOnsets = onsets(left)
            val incomingOnsets = onsets(right)
            val contentEndMs = outgoingSeconds * 1000L
            val recipe = AdaptiveTransitionPlanner.plan(
                outgoingGridTail = ExtendedBeatGrid(outgoingBpm, 0L, 1f),
                incomingGridHead = ExtendedBeatGrid(incomingBpm, incomingPhaseMs * 48L, 1f),
                outgoingFeaturesTail = null,
                incomingFeaturesHead = null,
                requestedCrossfadeMs = config.crossfadeMs,
                contentEndMs = contentEndMs,
                incomingDurationMs = 12_000L,
                maxTempoChange = config.maxTempoChange,
            )
            val plan = assertNotNull(AdaptiveTransitionPlanner.toCrossfadePlan(recipe, contentEndMs))
            val fadeEndSample = ((plan.startMs + plan.overlapMs) * sampleRate / 1000).toInt()
            val overlapOnsets = incomingOnsets.filter { it < minOf(handoffOutputSample, fadeEndSample) }
            assertTrue(overlapOnsets.size >= fadeBeats - 1,
                "expected at least ${fadeBeats - 1} incoming clicks during overlap; got ${overlapOnsets.map(::ms)}")
            val outgoingPeriodSamples = sampleRate / 2
            assertTrue(outgoingOnsets.isNotEmpty(), "outgoing source clicks missing")
            for (outgoingOnset in outgoingOnsets) {
                val expected = (outgoingOnset.toDouble() / outgoingPeriodSamples).roundToInt() * outgoingPeriodSamples
                assertTrue(ms(abs(outgoingOnset - expected)) <= 20.0,
                    "outgoing source onset ${ms(outgoingOnset)}ms differs from generated grid ${ms(expected)}ms")
            }
            for (incomingOnset in overlapOnsets) {
                val expected = (incomingOnset.toDouble() / outgoingPeriodSamples).roundToInt() * outgoingPeriodSamples
                val gridErrorMs = ms(abs(incomingOnset - expected))
                assertTrue(gridErrorMs <= 20.0,
                    "incoming click ${ms(incomingOnset)}ms differs from generated outgoing grid ${ms(expected)}ms by ${gridErrorMs}ms; bpm=$incomingBpm beats=$fadeBeats")
                // The boundary beat belongs to recovery, where outgoing gain is zero.
                // Its slightly earlier native onset still needs the generated-grid assertion.
                if (expected < fadeEndSample) {
                    val nearestOutgoing = outgoingOnsets.minBy { abs(it - incomingOnset) }
                    val errorMs = ms(abs(nearestOutgoing - incomingOnset))
                    assertTrue(errorMs <= 20.0,
                        "source clicks differ by ${errorMs}ms at incoming=${ms(incomingOnset)}ms, outgoing=${ms(nearestOutgoing)}ms; bpm=$incomingBpm beats=$fadeBeats")
                } else {
                    assertTrue(expected == fadeEndSample,
                        "incoming overlap continued beyond outgoing end: ${ms(incomingOnset)}ms")
                }
            }

            val nativePeriodMs = 60_000.0 / incomingBpm
            val postOnsets = incomingOnsets.filter { it >= handoffOutputSample }
            assertTrue(postOnsets.size >= 4, "insufficient post-handoff clicks: ${postOnsets.map(::ms)}")
            for ((first, next) in postOnsets.zipWithNext()) {
                assertTrue(abs(ms(next - first) - nativePeriodMs) <= 20.0,
                    "post-handoff interval ${ms(next - first)}ms differs from native ${nativePeriodMs}ms")
            }
            // A repeated/skipped source interval at the boundary must not hide behind a correct later BPM.
            val lastOverlap = incomingOnsets.last { it < handoffOutputSample }
            val firstNative = postOnsets.first()
            val boundaryMs = ms(firstNative - lastOverlap)
            assertTrue(boundaryMs >= minOf(500.0, nativePeriodMs) - 20.0 &&
                boundaryMs <= maxOf(500.0, nativePeriodMs) + 20.0,
                "handoff repeated or skipped a click interval: ${boundaryMs}ms")
            val summaries = logs.infoLines()
            assertTrue(summaries.size == 1, "expected one INFO line for the entire transition: $summaries")
            val summary = summaries.single()
            assertTrue(summary.contains("beatMatched=true"), summary)
            assertTrue(summary.contains("targetBpm=120.0") && summary.contains("fadeBeats=$fadeBeats"), summary)
            assertTrue(summary.contains("outgoing={bpm=120.0") && summary.contains("incoming={bpm=$incomingBpm"), summary)
            assertTrue(summary.contains("key=unknown(synthetic_fixture)") && summary.contains("rhythmStability=1.0"), summary)
            assertTrue(!summary.contains('\n') && !summary.contains('\r'), summary)
            mixer.noteUnderrun(960, 960)
            assertTrue(logs.messages.none { it.second.contains("crossfade progress") || it.second.contains("crossfade sub starvation") || it.second.contains("crossfade handoff") }, logs.messages.toString())
        } finally {
            logs.close()
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
            if (abs(samples[i]) > 0.003f && i - last > sampleRate / 5) {
                result.add(i)
                last = i
            }
        }
        return result
    }

    private fun ms(samples: Int): Double = samples * 1000.0 / sampleRate

    private fun cacheGrid(track: AudioTrack, grid: ExtendedBeatGrid) {
        AnalysisCaches.bpm[track.identifier] = BpmDetector.BpmResult(grid.bpm, grid.phaseMs, grid.bpmConfidence)
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
                val value = if (i - onset in 0L until 96L) 20_000 else 0
                shortLE(if (channel == 0) value else 0)
                shortLE(if (channel == 1) value else 0)
            }
        }
        return file
    }
}
