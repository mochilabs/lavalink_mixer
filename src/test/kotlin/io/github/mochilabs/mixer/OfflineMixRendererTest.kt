package io.github.mochilabs.mixer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OfflineMixRendererTest {
    @Test
    fun `render mixes two synthetic wav tracks to output file`() {
        val fileA = File.createTempFile("offline-a-", ".wav").apply { deleteOnExit() }
        val fileB = File.createTempFile("offline-b-", ".wav").apply { deleteOnExit() }
        val output = File.createTempFile("offline-out-", ".wav").apply { deleteOnExit() }

        OfflineMixRenderer.writeClickWav(fileA, bpm = 120f, seconds = 4, channel = 0)
        OfflineMixRenderer.writeClickWav(fileB, bpm = 120f, seconds = 4, channel = 1)

        val renderedSamples = OfflineMixRenderer.render(
            fileA = fileA,
            fileB = fileB,
            outputWav = output,
            crossfadeMs = 2000L,
        )

        assertTrue(output.exists())
        assertTrue(output.length() > 44L, "WAV header plus data must be written")
        assertTrue(renderedSamples > 0, "rendered sample count must be positive")
    }
}
