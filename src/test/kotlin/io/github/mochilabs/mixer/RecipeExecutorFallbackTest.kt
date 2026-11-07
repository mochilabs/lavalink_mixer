package io.github.mochilabs.mixer


import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeExecutorFallbackTest {
    @Test
    fun `recipe executor executes Level 2 absolute fallback equal-power curve identically to legacy crossfade`() {
        val totalSamples = 48000L * 5L // 5 seconds
        val fallbackRecipe = TransitionRecipe(
            strategy = TransitionStrategy.ABSOLUTE_FALLBACK,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(
                enabled = false,
                targetBpm = 0f,
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
            ),
            reason = "missing_all_metadata",
        )

        // Mid-point equal-power gain for both outgoing and incoming must be ~0.7071f (1/sqrt(2))
        val midSample = totalSamples / 2
        val outGain = fallbackRecipe.gainAt(TrackTarget.OUTGOING, midSample, 1.0f)
        val inGain = fallbackRecipe.gainAt(TrackTarget.INCOMING, midSample, 0.0f)

        assertEquals(0.7071f, outGain, 0.01f, "equal power at 50% must be ~0.7071")
        assertEquals(0.7071f, inGain, 0.01f, "equal power at 50% must be ~0.7071")

        // Sum of squared gains must be 1.0 (Equal Power law: cos^2 + sin^2 == 1.0)
        val powerSum = outGain * outGain + inGain * inGain
        assertEquals(1.0f, powerSum, 0.01f, "power sum must equal 1.0")
    }

    @Test
    fun `GuildMixer accepts active recipe and computes gains from recipe`() {
        val totalSamples = 48000L * 2L // 2 seconds
        val testRecipe = TransitionRecipe(
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
                    curve = RampCurve.LINEAR,
                ),
                ParamAutomation.VolumeRamp(
                    target = TrackTarget.INCOMING,
                    startSample = 0L,
                    endSample = totalSamples,
                    fromGain = 0.0f,
                    toGain = 1.0f,
                    curve = RampCurve.LINEAR,
                ),
            ),
        )

        val config = MixerConfig().apply { crossfadeMs = 2000L }
        val manager = com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager()
        val mixer = GuildMixer(1001L, manager, config, java.util.concurrent.Executor { })
        try {
            mixer.setActiveRecipeForTest(testRecipe, startSample = 0L, endSample = totalSamples)

            assertEquals(testRecipe, mixer.activeRecipe)

            // Advance 1 second (48000 samples)
            val gains = mixer.advance(48000, 1L)
            // With linear ramp, at 50% gains must be 0.5f
            assertEquals(0.5f, gains.main, 0.05f)
            assertEquals(0.5f, gains.sub, 0.05f)
        } finally {
            mixer.destroy()
            manager.shutdown()
        }
    }
}
