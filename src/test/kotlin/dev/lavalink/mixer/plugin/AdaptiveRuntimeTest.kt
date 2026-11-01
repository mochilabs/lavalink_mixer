package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.lavalink.api.IPlayer
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveRuntimeTest {
    private class Fixture : AutoCloseable {
        val manager = DefaultAudioPlayerManager()
        val tasks = ArrayDeque<Runnable>()
        val mixer = GuildMixer(91919L, manager, MixerConfig().apply {
            bpmDetectionEnabled = true
            beatmatchEnabled = true
        }, Executor { tasks.addLast(it) })
        var playing: AudioTrack? = null
        val player = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "getPlayingTrack" -> playing
                "isPaused" -> false
                "getVolume" -> 100
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioPlayer
        init {
            val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IPlayer::class.java)) { _, method, _ ->
                when (method.name) {
                    "getAudioPlayer" -> player
                    "getGuildId" -> 91919L
                    "getTrack" -> playing
                    else -> null
                }
            } as IPlayer
            mixer.attachMain(host)
            mixer.reset(1, UUID.randomUUID().toString(), 0)
        }
        override fun close() { mixer.destroy(); manager.shutdown() }
    }

    @Test
    fun `cached current tempo carries identity and disappears on disable stop or uncached replacement`() {
        Fixture().use { f ->
            val first = track()
            val second = track()
            f.playing = first
            AnalysisCaches.bpmTail[first.identifier] = BeatGrid(120f, 0, 1f)
            assertEquals(GuildMixer.CurrentTempo(first.info.identifier, 120f), f.mixer.currentTempo())
            f.playing = second
            assertNull(f.mixer.currentTempo(), "old main BPM must not survive a replacement")
            AnalysisCaches.bpmHead[second.identifier] = BeatGrid(125f, 0, 1f)
            assertEquals(GuildMixer.CurrentTempo(second.info.identifier, 125f), f.mixer.currentTempo())
            f.mixer.updateBpm(false)
            assertNull(f.mixer.currentTempo())
            f.mixer.updateBpm(true)
            f.playing = null
            assertNull(f.mixer.currentTempo())
        }
    }

    @Test
    fun `queued warmup never owns or promotes next slot and rejects duplicate queued requests`() {
        Fixture().use { f ->
            val next = track()
            val warmup = track()
            f.mixer.queueNext(CompletableFuture.completedFuture(next))
            val before = f.mixer.playbackState
            assertTrue(f.mixer.requestPreanalysis(warmup))
            assertFalse(f.mixer.requestPreanalysis(track()))
            assertEquals(next.identifier, f.mixer.nextTrackIdentifier())
            // Complete through the cached path without invoking a decoder: this exercises
            // the same cache-only completion guard used by an already-warmed request.
            val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 1f,
                KeyEstimate.Unknown("fixture"), emptyList())
            AnalysisCaches.bpm[warmup.identifier] = BpmDetector.BpmResult(120f, 0, 1f)
            AnalysisCaches.bpmHead[warmup.identifier] = BeatGrid(120f, 0, 1f)
            AnalysisCaches.bpmTail[warmup.identifier] = BeatGrid(120f, 0, 1f)
            AnalysisCaches.featuresHead[warmup.identifier] = features
            AnalysisCaches.featuresTail[warmup.identifier] = features
            f.tasks.removeFirst().run()
            assertEquals(before, f.mixer.playbackState)
            assertEquals(next.identifier, f.mixer.nextTrackIdentifier())
            assertFalse(f.mixer.requestPreanalysis(warmup), "cached warmup must not reschedule")
        }
    }

    @Test
    fun `preserve context participates in idempotency and is cleared by invalidation`() {
        Fixture().use { f ->
            f.playing = track()
            val next = track()
            assertIs<ArmResult.Success>(f.mixer.arm(1, "same", next, false, "encoded", true))
            assertIs<ArmResult.Duplicate>(f.mixer.arm(1, "same", next, false, "encoded", true))
            assertIs<ArmResult.PayloadMismatch>(f.mixer.arm(1, "same", next, false, "encoded", false))
            f.mixer.poll()
            f.mixer.poll()
            assertEquals("preserve", f.mixer.transitionStatus.kind)
            assertEquals("preserve_context", f.mixer.transitionStatus.reason)
            assertIs<InvalidateResult.Success>(f.mixer.invalidate(2, "invalidate"))
            assertEquals("none", f.mixer.transitionStatus.kind)
            assertIs<ArmResult.Success>(f.mixer.arm(2, "next-arm", next, false, "encoded", false))
            f.mixer.poll()
            assertEquals("short_crossfade", f.mixer.transitionStatus.kind)
            assertEquals("missing_features", f.mixer.transitionStatus.reason)
        }
    }

    private fun track(): AudioTrack {
        val id = "adaptive-runtime-${UUID.randomUUID()}"
        lateinit var track: AudioTrack
        track = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioTrack::class.java)) { proxy, method, args ->
            when (method.name) {
                "getInfo" -> AudioTrackInfo("fixture", "fixture", 10000, id, false, "fixture", null, null)
                "getIdentifier" -> id
                "getDuration" -> 10000L
                "getPosition" -> 0L
                "isSeekable" -> true
                "makeClone" -> track
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioTrack
        return track
    }
}
