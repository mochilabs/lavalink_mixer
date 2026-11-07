package io.github.mochilabs.mixer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveAutomixEndToEndTest {
    @Test
    fun `end-to-end pipeline analyzes tracks plans recipe and renders mixed audio`() {
        val fileA = File.createTempFile("automix-e2e-a-", ".wav").apply { deleteOnExit() }
        val fileB = File.createTempFile("automix-e2e-b-", ".wav").apply { deleteOnExit() }
        val outputWav = File.createTempFile("automix-e2e-out-", ".wav").apply { deleteOnExit() }

        OfflineMixRenderer.writeClickWav(fileA, bpm = 120f, seconds = 10, channel = 0)
        OfflineMixRenderer.writeClickWav(fileB, bpm = 120f, seconds = 10, channel = 1)

        val gridA = ExtendedBeatGrid(120f, 0L, 0.95f, 0L, 0.85f, 0.75f)
        val gridB = ExtendedBeatGrid(120f, 0L, 0.95f, 0L, 0.85f, 0.75f)

        val featuresA = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())
        val featuresB = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("percussive"), emptyList())

        // 1. Planner generates recipe
        val recipe = AdaptiveTransitionPlanner.plan(
            outgoingGridTail = gridA,
            incomingGridHead = gridB,
            outgoingFeaturesTail = featuresA,
            incomingFeaturesHead = featuresB,
            requestedCrossfadeMs = 2000L,
            contentEndMs = 10_000L,
        )
        assertNotNull(recipe)

        // 2. OfflineMixRenderer renders audio through GuildMixer + MixerFilter
        val renderedSamples = OfflineMixRenderer.render(
            fileA = fileA,
            fileB = fileB,
            outputWav = outputWav,
            crossfadeMs = 2000L,
            gridA = gridA,
            gridB = gridB,
        )

        assertTrue(outputWav.exists(), "output WAV file must be created")
        assertTrue(outputWav.length() > 44L, "output WAV file must contain audio data")
        assertTrue(renderedSamples > 48000L, "rendered at least 1 second of mixed output")
    }
}
