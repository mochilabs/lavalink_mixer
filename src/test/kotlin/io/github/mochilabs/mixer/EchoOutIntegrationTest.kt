package io.github.mochilabs.mixer

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EchoOutIntegrationTest {
    private val sampleRate = 48000

    @Test
    fun `MixerFilter executes Echo Out with beat-synced feedback decay and linear termination`() {
        val totalSamples = 96000L // 2.0 seconds
        val delaySamples = 12000  // 250ms (1/2-beat @ 120 BPM)
        val termSamples = 72000   // 1.5s termination ramp

        val recipe = TransitionRecipe(
            strategy = TransitionStrategy.ECHO_OUT,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(enabled = false, targetBpm = 120f),
            automations = listOf(
                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, 0L, 0.0f, 0.0f),
                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, 0L, 0.0f, 0.0f), // silence incoming for clean echo measurement
                ParamAutomation.EchoTrigger(
                    atSample = 0L,
                    delaySamples = delaySamples,
                    feedback = 0.60f,
                    terminationRampSamples = termSamples,
                ),
            ),
        )

        val config = MixerConfig().apply { crossfadeMs = 2000L }
        val mixer = GuildMixer(3001L, com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager(), config, java.util.concurrent.Executor { })

        val output = mutableListOf<Float>()
        val downstream = object : com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                for (i in offset until (offset + length)) output.add(input[0][i])
            }
            override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val filter = MixerFilter(mixer, downstream)

        // Seed an impulse in the main track just before transition starts
        val chunkSize = 960
        val impulseChunk = Array(2) { FloatArray(chunkSize) }
        impulseChunk[0][chunkSize - 1] = 1.0f
        impulseChunk[1][chunkSize - 1] = 1.0f

        // Feed pre-roll chunk to seed delay buffer
        // Feed pre-roll chunk to seed delay buffer during normal playback
        filter.process(impulseChunk, 0, chunkSize)
        output.clear()

        // Activate transition recipe at startSample = chunkSize
        mixer.setActiveRecipeForTest(recipe, startSample = chunkSize.toLong(), endSample = chunkSize.toLong() + totalSamples)

        // Process 2 seconds of silence; echo effect should ring out and terminate
        val silentChunk = Array(2) { FloatArray(chunkSize) }
        val totalChunks = (totalSamples / chunkSize).toInt()
        for (c in 0 until totalChunks) {
            filter.process(silentChunk, 0, chunkSize)
        }

        assertEquals(totalSamples.toInt(), output.size)

        // Echo impulse must appear around delaySamples offset (250ms = 12000 samples)
        val peakAroundDelay = (11900..12100).maxOf { abs(output[it]) }
        assertTrue(peakAroundDelay > 0.8f, "first echo bounce must be present around 12000 samples (got $peakAroundDelay)")

        // After termination duration (termSamples = 72000), echo must be completely silent (< 1e-4)
        val tailMax = output.subList(termSamples + 2000, output.size).maxOf { abs(it) }
        assertTrue(tailMax < 1e-4f, "echo tail must terminate cleanly to 0.0 after termination ramp (got $tailMax)")
    }
}
