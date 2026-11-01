package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TailCutDeciderTest {
    private val confirm = 1000L
    private val window = 15000L

    private fun loud(d: TailCutDecider, ms: Long) {
        d.note(0.5f, (ms * 48).toInt(), 0.001f)
    }

    private fun quiet(d: TailCutDecider, ms: Long) {
        d.note(0.0f, (ms * 48).toInt(), 0.001f)
    }

    @Test
    fun `no cut before confirmation`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 500)
        assertFalse(d.shouldCut(200000, 210000, confirm, window))
    }

    @Test
    fun `cuts inside tail window after confirmation`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 1200)
        assertTrue(d.shouldCut(200000, 210000, confirm, window))
    }

    @Test
    fun `no cut mid-track even when silent`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 5000)
        assertFalse(d.shouldCut(60000, 210000, confirm, window))
    }

    @Test
    fun `sound resets the timer`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 900)
        loud(d, 100)
        quiet(d, 900)
        assertFalse(d.shouldCut(200000, 210000, confirm, window))
        assertEquals(43200, d.silentSamples)
    }

    @Test
    fun `early track position never cuts`() {
        val d = TailCutDecider()
        quiet(d, 10000)
        assertFalse(d.shouldCut(3000, 210000, confirm, window))
    }

    @Test
    fun `streams need longer evidence`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 1500)
        assertFalse(d.shouldCut(60000, -1, confirm, window))
        quiet(d, 2000)
        assertTrue(d.shouldCut(60000, -1, confirm, window))
    }

    @Test
    fun `decode lookahead waits for playback position to reach silent start`() {
        val d = TailCutDecider()
        // Silence starts at 196_000ms and runs for 1200ms (up to 197_200ms on decode chain).
        d.note(0.5f, 196_000 * 48, 0.001f, 196_000L * 48)
        d.note(0.0f, 1_200 * 48, 0.001f, 197_200L * 48)
        // While playback is still at 192_000ms (buffered audible audio), do not cut early.
        assertFalse(d.shouldCut(192_000, 200_000, confirm, window))
        assertTrue(d.isConfirmedBefore(196_000, 200_000, confirm, window))
        assertFalse(d.isConfirmedBefore(195_000, 200_000, confirm, window))
        // Once playback reaches the silent boundary at 196_000ms, cut immediately.
        assertTrue(d.shouldCut(196_000, 200_000, confirm, window))
    }
}
