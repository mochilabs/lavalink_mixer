package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.lavalink.api.IPlayer
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerCallbackLockTest {
    @Test
    fun `real player end dispatch releases its lock before queued advancement waits for mutation lock`() = verifyCallback(false)

    @Test
    fun `a deferred end callback cannot advance a replacement queue slot`() = verifyCallback(true)

    private fun verifyCallback(supersede: Boolean) {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(772L, manager, MixerConfig(), java.util.concurrent.Executor { })
        val player = manager.createPlayer()
        val played = AtomicReference<AudioTrack>()
        val advanced = CountDownLatch(1)
        val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getAudioPlayer" -> player
                "getGuildId" -> 772L
                "play" -> { played.set(args!![0] as AudioTrack); advanced.countDown(); null }
                else -> null
            }
        } as IPlayer
        mixer.attachMain(host)
        val next = track()
        mixer.queueNext(CompletableFuture.completedFuture(next))
        val mutationLock = GuildMixer::class.java.getDeclaredField("playerMutationLock").apply { isAccessible = true }.get(mixer)
        val dispatch = player.javaClass.getDeclaredMethod("dispatchEvent", AudioEvent::class.java).apply { isAccessible = true }
        val returned = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            synchronized(mutationLock) {
                pool.submit {
                    try { dispatch.invoke(player, TrackEndEvent(player, track(), AudioTrackEndReason.FINISHED)) }
                    finally { returned.countDown() }
                }
                assertTrue(returned.await(1, TimeUnit.SECONDS), "player callback waited for mutation lock while holding trackSwitchLock")
                if (supersede) {
                    mixer.clearNext()
                    mixer.queueNext(CompletableFuture.completedFuture(track()))
                }
            }
            if (supersede) {
                val protocol = GuildMixer::class.java.getDeclaredField("protocol").apply { isAccessible = true }.get(mixer) as ProtocolController
                val drained = CountDownLatch(1)
                protocol.executeAction { drained.countDown() }
                assertTrue(drained.await(5, TimeUnit.SECONDS))
                assertNull(played.get(), "stale callbacks must not play a replacement")
                assertTrue(mixer.hasNext())
            } else {
                assertTrue(advanced.await(5, TimeUnit.SECONDS), "deferred end event did not advance")
                assertSame(next, played.get())
            }
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            mixer.destroy()
            player.destroy()
            manager.shutdown()
        }
    }

    private fun track(): AudioTrack {
        val id = "callback-lock-${UUID.randomUUID()}"
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioTrack::class.java)) { proxy, method, args ->
            when (method.name) {
                "getIdentifier" -> id
                "getInfo" -> AudioTrackInfo("fixture", "fixture", 10000, id, false, "fixture", null, null)
                "getDuration" -> 10000L
                "getPosition" -> 0L
                "isSeekable" -> true
                "makeClone" -> proxy
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioTrack
    }
}
