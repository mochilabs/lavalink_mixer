package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A destroyed mixer must refuse to create players. Announcement resolution and
 * overlap preparation finish asynchronously, and before the destroyed flag they
 * could create an AudioPlayer on a mixer nobody would drain or destroy again.
 */
class MixerLifecycleTest {
    private class CountingManager : DefaultAudioPlayerManager() {
        val created = AtomicInteger()
        override fun createPlayer(): AudioPlayer {
            created.incrementAndGet()
            return super.createPlayer()
        }
    }

    private fun manager() = CountingManager().apply {
        configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
    }

    /** Never decoded: the destroyed checks run before the track is touched. */
    private fun fakeTrack(): AudioTrack = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(AudioTrack::class.java),
    ) { _, method, _ -> error("track must not be used after destroy (${method.name})") } as AudioTrack

    @Test
    fun `sequential overlay start after destroy creates no player`() {
        val manager = manager()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mixer = GuildMixer(1L, manager, MixerConfig(), executor)
            mixer.destroy()
            val before = manager.created.get()
            assertFailsWith<MixerDestroyedException> {
                mixer.startOverlay(fakeTrack(), 0.3f, 1L, AnnounceMode.SEQUENTIAL)
            }
            assertEquals(before, manager.created.get(), "a destroyed mixer created a secondary player")
        } finally {
            executor.shutdownNow()
            manager.shutdown()
        }
    }

    @Test
    fun `parallel overlay start after destroy creates no player`() {
        val manager = manager()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mixer = GuildMixer(2L, manager, MixerConfig(), executor)
            mixer.destroy()
            val before = manager.created.get()
            assertFailsWith<MixerDestroyedException> {
                mixer.startOverlay(fakeTrack(), 0.3f, 1L, AnnounceMode.PARALLEL)
            }
            assertEquals(before, manager.created.get(), "a destroyed mixer created a parallel overlay player")
        } finally {
            executor.shutdownNow()
            manager.shutdown()
        }
    }
}
