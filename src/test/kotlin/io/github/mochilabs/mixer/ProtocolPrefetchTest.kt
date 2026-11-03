package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.IPlayer
import kotlinx.serialization.json.JsonObjectBuilder
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolPrefetchTest {
    private class Fixture : AutoCloseable {
        var position = 50_000L
        val event = CountDownLatch(1)
        val secondEvent = CountDownLatch(1)
        val eventCount = AtomicInteger()
        val track = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioTrack::class.java)) { _, method, _ ->
            when (method.name) {
                "getDuration" -> 120_000L
                "getPosition" -> position
                else -> null
            }
        } as AudioTrack
        val controller = ProtocolController(object : MixerCore {
            override val guildId = 71L
            override val lock = Any()
            override val playerMutationLock = Any()
            override val playerManager: AudioPlayerManager get() = error("not needed")
            override val mainPlayer: IPlayer? = null
            override val playingTrack: AudioTrack get() = track
            override val crossfadeMs = 5_000L
            override val prefetchLeadMs = 67_000L
            override val isXfadeActive = false
            override val isOverlayActive = false
            override fun safePosition(track: AudioTrack) = position
            override fun clearNextLocked() = Unit
            override fun preloadArmedTrackLocked(track: AudioTrack) = Unit
            override fun scheduleArmedAnalysis(track: AudioTrack) = Unit
            override fun clearMaskingLocked() = Unit
            override fun restoreUnityGainAfterStopLocked() = Unit
            override fun syncPlayingStateOnResetLocked(playing: AudioTrack?) = Unit
            override fun abortXfadeOutsideLock(reason: String) = Unit
            override fun cancelOverlayOutsideLock() = false
            override fun notifyEvent(type: String, body: JsonObjectBuilder.() -> Unit) {
                if (type == "MixerNeedNextTrackEvent") {
                    if (eventCount.incrementAndGet() == 1) event.countDown() else secondEvent.countDown()
                }
            }
        })
        init { controller.playbackState = PlaybackState.PLAYING }
        override fun close() = controller.shutdown()
    }

    @Test
    fun `BPM scan prefetches next track before the final crossfade window`() {
        Fixture().use { f ->
            f.controller.checkJitWindow()
            assertTrue(f.event.await(1, TimeUnit.SECONDS), "a 30s head and tail scan needs time before a 5s fade")
        }
    }

    @Test
    fun `queue changes after an early empty arm request the next track again`() {
        Fixture().use { f ->
            f.controller.checkJitWindow()
            assertTrue(f.event.await(1, TimeUnit.SECONDS))
            assertIs<ArmResult.Success>(f.controller.arm(1, "empty", null, true))
            f.position = 60_000L
            val invalidated = assertIs<InvalidateResult.Success>(f.controller.invalidate(2, "changed"))
            assertTrue(invalidated.refiredNeedNext, "the new queue head must be requested before the old 12s window")
            assertTrue(f.secondEvent.await(1, TimeUnit.SECONDS), "the refire must dispatch after the first event")
        }
    }
}
