package io.github.mochilabs.mixer


import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BassSwapIntegrationTest {
    private val sampleRate = 48000

    @Test
    fun `MixerFilter executes Bass Swap with smooth transition and no click impulse`() {
        val totalSamples = 96000L // 2 seconds
        val swapSample = 48000L  // 1 second

        val recipe = TransitionRecipe(
            strategy = TransitionStrategy.PHRASE_MATCHED_BASS_SWAP,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(
                enabled = true,
                targetBpm = 120f,
                startSample = 0L,
                handoffSample = totalSamples,
            ),
            automations = listOf(
                ParamAutomation.VolumeRamp(
                    target = TrackTarget.OUTGOING,
                    startSample = 0L,
                    endSample = totalSamples,
                    fromGain = 1.0f,
                    toGain = 0.0f,
                    curve = RampCurve.EQUAL_POWER,
                ),
                ParamAutomation.VolumeRamp(
                    target = TrackTarget.INCOMING,
                    startSample = 0L,
                    endSample = totalSamples,
                    fromGain = 0.0f,
                    toGain = 1.0f,
                    curve = RampCurve.EQUAL_POWER,
                ),
                ParamAutomation.BassCut(
                    target = TrackTarget.INCOMING,
                    atSample = 0L,
                    muteLow = true,
                ),
                ParamAutomation.BassCut(
                    target = TrackTarget.INCOMING,
                    atSample = swapSample,
                    muteLow = false,
                ),
                ParamAutomation.BassCut(
                    target = TrackTarget.OUTGOING,
                    atSample = swapSample,
                    muteLow = true,
                ),
            ),
        )

        val config = MixerConfig().apply { crossfadeMs = 2000L }
        val manager = com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager()
        val mixer = GuildMixer(2001L, manager, config, java.util.concurrent.Executor { })
        try {
            mixer.setActiveRecipeForTest(recipe, startSample = 0L, endSample = totalSamples)

        // Mock downstream filter capturing mixed output
        var capturedSamples = 0
        val output = mutableListOf<Float>()
        val downstream = object : com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter {
            override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                for (i in offset until (offset + length)) {
                    output.add(input[0][i])
                }
                capturedSamples += length
            }
            override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }

        val filter = MixerFilter(mixer, downstream)

        // Provide 2.0 seconds of audio in 20ms chunks (960 samples per chunk = 100 chunks)
        val chunkSize = 960
        val mainChunk = Array(2) { FloatArray(chunkSize) { 0.5f } } // DC/low frequency signal
        val totalChunks = (totalSamples / chunkSize).toInt()

        for (c in 0 until totalChunks) {
            filter.process(mainChunk, 0, chunkSize)
        }

        assertTrue(capturedSamples >= totalSamples.toInt())
        // Verify no sudden sample-to-sample discontinuities (> 0.25) across the swap boundary
        for (i in 1 until output.size) {
            val delta = abs(output[i] - output[i - 1])
            assertTrue(delta < 0.25f, "sample-to-sample step at $i was $delta, must be smooth (< 0.25)")
        }
        } finally {
            mixer.destroy()
            manager.shutdown()
        }
    }

    @Test
    fun `MixerFilter attenuates sub-bass on track when bass is muted by recipe`() {
        val totalSamples = 96000L
        val swapSample = 48000L
        val recipe = TransitionRecipe(
            strategy = TransitionStrategy.PHRASE_MATCHED_BASS_SWAP,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(enabled = true, targetBpm = 120f, startSample = 0L, handoffSample = totalSamples),
            automations = listOf(
                ParamAutomation.VolumeRamp(TrackTarget.OUTGOING, 0L, totalSamples, 0.0f, 0.0f),
                ParamAutomation.VolumeRamp(TrackTarget.INCOMING, 0L, totalSamples, 1.0f, 1.0f),
                ParamAutomation.BassCut(TrackTarget.INCOMING, atSample = 0L, muteLow = true),
                ParamAutomation.BassCut(TrackTarget.INCOMING, atSample = swapSample, muteLow = false),
            ),
        )

        val config = MixerConfig().apply { crossfadeMs = 2000L }
        val manager = com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager()
        val mixer = GuildMixer(2002L, manager, config, java.util.concurrent.Executor { })
        try {
            mixer.setActiveRecipeForTest(recipe, startSample = 0L, endSample = totalSamples)

        // Feed 60Hz pure sine wave to sub-siphon
        val subSine = FloatArray(totalSamples.toInt()) { i ->
            kotlin.math.sin(2.0 * Math.PI * 60.0 * i / sampleRate).toFloat()
        }
        mixer.siphon.push(Array(2) { subSine }, 0, totalSamples.toInt())

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
        val chunkSize = 960
        val silentMain = Array(2) { FloatArray(chunkSize) }
        val totalChunks = (totalSamples / chunkSize).toInt()

        for (c in 0 until totalChunks) {
            filter.process(silentMain, 0, chunkSize)
        }

        // Measure RMS before swap (muted low) vs after swap (unmuted low)
        val preSwapSamples = output.subList(4800, 43200) // 100ms..900ms
        val postSwapSamples = output.subList(52800, 91200) // 1100ms..1900ms

        val preRms = kotlin.math.sqrt(preSwapSamples.map { it * it }.average()).toFloat()
        val postRms = kotlin.math.sqrt(postSwapSamples.map { it * it }.average()).toFloat()

        assertTrue(preRms < 0.20f, "pre-swap sub-bass must be attenuated when bass is muted (got $preRms)")
            assertTrue(postRms > 0.55f, "post-swap sub-bass must pass when bass is unmuted (got $postRms)")
        } finally {
            mixer.destroy()
            manager.shutdown()
        }
    }
}
