package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.lavalink.api.IPlayer
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapPreparationTest {
    @Test
    fun `delayed secondary PCM keeps main audible and starts a late fade without discarding new audio`() = verifyDelayed(false)

    @Test
    fun `queue invalidation cancels an in flight decoder preparation`() = verifyDelayed(true)

    private fun verifyDelayed(cancel: Boolean) {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(773L, manager, MixerConfig().apply { fadeInMs = 0 }, java.util.concurrent.Executor { })
        val outgoing = track(7000L)
        val incoming = track(2400L)
        val subTrack = AtomicReference<AudioTrack>()
        val started = CountDownLatch(1)
        val sub = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "playTrack" -> { subTrack.set(args!![0] as AudioTrack); started.countDown(); null }
                "getPlayingTrack" -> subTrack.get()
                "provide" -> false
                "stopTrack", "destroy" -> { subTrack.set(null); null }
                else -> null
            }
        } as AudioPlayer
        val main = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "getPlayingTrack" -> outgoing
                "isPaused" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioPlayer
        val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IPlayer::class.java)) { _, method, _ ->
            when (method.name) { "getAudioPlayer" -> main; "getGuildId" -> 773L; else -> null }
        } as IPlayer
        mixer.attachMain(host)
        GuildMixer::class.java.getDeclaredField("subPlayer").apply { isAccessible = true }.set(mixer, sub)
        try {
            mixer.queueNext(CompletableFuture.completedFuture(incoming))
            mixer.syncChainPos(7000)
            mixer.poll()
            mixer.poll()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(mixer.isXfadeActive(), "an empty secondary must not arm the fade")
            assertTrue(mixer.hasNext(), "natural end must retain a gapless fallback during preparation")
            val input = Array(2) { FloatArray(960) { 0.1f } }
            val filter = mixer.newFilter()
            filter.process(input, 0, 960)
            assertEquals(0.1f, input[0][0], "main audio must remain at unity while waiting for PCM")

            if (cancel) {
                mixer.clearNext()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (subTrack.get() != null && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(null, subTrack.get(), "cancelled decoder must be stopped")
                assertFalse(mixer.isXfadeActive())
                assertFalse(mixer.hasNext())
                return
            }

            mixer.syncChainPos(8000)
            mixer.siphon.push(Array(2) { FloatArray(48000) { 0.2f } }, 0, 48000)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!mixer.isXfadeActive() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(mixer.isXfadeActive(), "ready PCM must arm the fade")
            assertFalse(mixer.hasNext())
            filter.process(input, 0, 960)
            assertTrue(input[0][0] > 0.1f, "incoming PCM must be mixed at the first ready chunk")
            assertEquals(960L, mixer.subOutputSamplesForTest())
            assertEquals(960L, mixer.subSourceSamples(), "startup delay must not discard a second of incoming content")
        } finally {
            mixer.destroy()
            manager.shutdown()
        }
    }

    @Test
    fun `secondary drain pauses while a second of captured PCM is banked`() {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(775L, manager, MixerConfig(), java.util.concurrent.Executor { })
        var provides = 0
        val track = track(0)
        val sub = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlayingTrack" -> track
                "provide" -> { provides++; false }
                else -> null
            }
        } as AudioPlayer
        GuildMixer::class.java.getDeclaredField("subPlayer").apply { isAccessible = true }.set(mixer, sub)
        try {
            mixer.siphon.push(Array(2) { FloatArray(48000) }, 0, 48000)
            repeat(20) { mixer.drain() }
            assertEquals(0, provides, "prefill must not run ahead of PCM consumption")
            mixer.siphon.discard(960)
            mixer.drain()
            assertEquals(1, provides)
        } finally { mixer.destroy(); manager.shutdown() }
    }

    @Test
    fun `a chunk crossing fade start keeps the prefix silent without consuming incoming samples`() {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(776L, manager, MixerConfig(), java.util.concurrent.Executor { })
        try {
            mixer.beginXfadeForTest(48000)
            GuildMixer::class.java.getDeclaredField("xfadeStartSample").apply { isAccessible = true }.setLong(mixer, 500L)
            GuildMixer::class.java.getDeclaredField("xfadeEndSample").apply { isAccessible = true }.setLong(mixer, 48000L)
            mixer.siphon.push(Array(2) { FloatArray(960) { 0.2f } }, 0, 960)
            mixer.advance(960, 1L)
            val out = Array(2) { FloatArray(960) }
            assertEquals(460, mixer.takeSub(960, 2, out))
            assertTrue(out[0].take(500).all { it == 0f })
            assertEquals(0.2f, out[0][500])
            assertEquals(460L, mixer.subSourceSamples())
        } finally { mixer.destroy(); manager.shutdown() }
    }

    private fun track(position: Long): AudioTrack {
        val id = "prepare-${UUID.randomUUID()}"
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioTrack::class.java)) { proxy, method, args ->
            when (method.name) {
                "getInfo" -> AudioTrackInfo("fixture", "fixture", 12000, id, false, "fixture", null, null)
                "getIdentifier" -> id
                "getDuration" -> 12000L
                "getPosition" -> position
                "isSeekable" -> true
                "makeClone" -> proxy
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioTrack
    }
}
