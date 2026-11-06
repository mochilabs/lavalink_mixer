package io.github.mochilabs.mixer


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionRecipeTest {
    @Test
    fun `evaluates volume ramps and bass cut automation correctly`() {
        val totalSamples = 96000L // 2.0s @ 48kHz
        val recipe = TransitionRecipe(
            strategy = TransitionStrategy.PHRASE_MATCHED_BASS_SWAP,
            totalSamples = totalSamples,
            sync = BeatSyncConfig(
                enabled = true,
                targetBpm = 120f,
                rateRatio = 1.0f,
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
                ParamAutomation.BassCut(
                    target = TrackTarget.INCOMING,
                    atSample = 0L,
                    muteLow = true,
                ),
                ParamAutomation.BassCut(
                    target = TrackTarget.INCOMING,
                    atSample = 48000L, // At 1 second, un-mute incoming bass
                    muteLow = false,
                ),
                ParamAutomation.BassCut(
                    target = TrackTarget.OUTGOING,
                    atSample = 48000L, // At 1 second, mute outgoing bass
                    muteLow = true,
                ),
            ),
        )

        // Mid-point gains (linear)
        assertEquals(0.5f, recipe.gainAt(TrackTarget.OUTGOING, 48000L, 1.0f), 0.01f)
        assertEquals(0.5f, recipe.gainAt(TrackTarget.INCOMING, 48000L, 0.0f), 0.01f)

        // Bass swap state before 48,000
        assertTrue(recipe.isBassMutedAt(TrackTarget.INCOMING, 24000L), "incoming bass must be muted before swap point")
        assertFalse(recipe.isBassMutedAt(TrackTarget.OUTGOING, 24000L), "outgoing bass must not be muted before swap point")

        // Bass swap state after 48,000
        assertFalse(recipe.isBassMutedAt(TrackTarget.INCOMING, 72000L), "incoming bass must be unmuted after swap point")
        assertTrue(recipe.isBassMutedAt(TrackTarget.OUTGOING, 72000L), "outgoing bass must be muted after swap point")
    }
}
