package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.player.event.TrackStartEvent
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.lavalink.api.IPlayer
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdaptiveTransitionRaceTest {
    private class PositionedTrack(initialPosition: Long = 0) {
        val id = "adaptive-race-${UUID.randomUUID()}"
        var position = initialPosition
        val seeks = mutableListOf<Long>()
        val track: AudioTrack = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioTrack::class.java)) { proxy, method, args ->
            when (method.name) {
                "getInfo" -> AudioTrackInfo("fixture", "fixture", 10000, id, false, "fixture", null, null)
                "getIdentifier" -> id
                "getDuration" -> 10000L
                "getPosition" -> position
                "setPosition" -> { position = args!![0] as Long; seeks += position; null }
                "isSeekable" -> true
                "makeClone" -> proxy
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioTrack
    }

    private class Fixture(silence: Boolean = false) : AutoCloseable {
        val manager = DefaultAudioPlayerManager()
        val mixer = GuildMixer(92929, manager, MixerConfig().apply {
            bpmDetectionEnabled = true
            beatmatchEnabled = true
            crossfadeEnabled = true
            crossfadeMs = 5000
            silenceSkipEnabled = silence
            fadeInMs = 0
        }, Executor { })
        var playing: AudioTrack? = null
        val listeners = mutableListOf<AudioEventListener>()
        val player = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AudioPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "getPlayingTrack" -> playing
                "isPaused" -> false
                "getVolume" -> 100
                "addListener" -> { listeners += args!![0] as AudioEventListener; null }
                "removeListener" -> { listeners -= args!![0] as AudioEventListener; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as AudioPlayer
        init {
            val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IPlayer::class.java)) { _, method, args ->
                when (method.name) {
                    "getAudioPlayer" -> player
                    "getGuildId" -> 92929L
                    "getTrack" -> playing
                    "play" -> { play(args!![0] as AudioTrack); null }
                    else -> null
                }
            } as IPlayer
            mixer.attachMain(host)
            mixer.reset(1, UUID.randomUUID().toString(), 0)
        }
        fun play(track: AudioTrack) {
            playing = track
            listeners.toList().forEach { it.onEvent(TrackStartEvent(player, track)) }
        }
        fun arm(track: PositionedTrack, preserve: Boolean = false) {
            assertIs<ArmResult.Success>(mixer.arm(1, UUID.randomUUID().toString(), track.track, false, track.id, preserve))
        }
        fun captureAction(): Any {
            val lock = GuildMixer::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(mixer)
            val decide = GuildMixer::class.java.getDeclaredMethod("decidePollLocked").apply { isAccessible = true }
            return synchronized(lock) { decide.invoke(mixer) }
        }
        fun execute(action: Any) {
            GuildMixer::class.java.declaredMethods.single { it.name == "executePoll" }.apply { isAccessible = true }.invoke(mixer, action)
        }
        override fun close() { mixer.destroy(); manager.shutdown() }
    }

    private fun cacheTrimFeatures(outgoing: PositionedTrack, incoming: PositionedTrack) {
        AnalysisCaches.trim[outgoing.id] = TrimPoints.NONE
        AnalysisCaches.trim[incoming.id] = TrimPoints(400, 0)
        val features = TransitionFeatures(0, 10000, emptyList(), 0.2f, 0.9f, KeyEstimate.Unknown("fixture"), emptyList())
        AnalysisCaches.featuresTail[outgoing.id] = features.copy(trailingSilenceMs = 800)
        AnalysisCaches.featuresHead[incoming.id] = features.copy(leadingSilenceMs = 800)
    }

    @Test
    fun `rearm after overlap planning preserves replacement slot and context`() {
        Fixture().use { f ->
            val outgoing = PositionedTrack(9500)
            val oldNext = PositionedTrack()
            val replacement = PositionedTrack()
            f.play(outgoing.track)
            f.arm(oldNext)
            f.captureAction() // Establish main identity before the planning tick.
            val stale = f.captureAction()
            assertEquals("StartOverlap", stale.javaClass.simpleName)
            f.arm(replacement, preserve = true) // Same epoch: track identity must fence the plan.
            f.execute(stale)
            assertSame(outgoing.track, f.playing)
            assertEquals(replacement.id, f.mixer.nextTrackIdentifier())
            assertEquals(0L, replacement.position)
            assertFalse(f.mixer.isXfadeActive())
            f.mixer.poll()
            assertEquals("preserve_context", f.mixer.transitionStatus.reason)
        }
    }

    @Test
    fun `rearm after silence trim planning does not advance replacement early`() {
        Fixture(silence = true).use { f ->
            val outgoing = PositionedTrack(9500)
            val oldNext = PositionedTrack()
            val replacement = PositionedTrack()
            cacheTrimFeatures(outgoing, oldNext)
            f.play(outgoing.track)
            f.arm(oldNext)
            f.captureAction()
            val stale = f.captureAction()
            assertEquals("AdvanceNext", stale.javaClass.simpleName)
            f.arm(replacement, preserve = true)
            f.execute(stale)
            assertSame(outgoing.track, f.playing)
            assertEquals(replacement.id, f.mixer.nextTrackIdentifier())
            assertEquals(0L, replacement.position)
        }
    }

    @Test
    fun `track start retains adaptive incoming position beyond cached silence lead`() {
        Fixture(silence = true).use { f ->
            val outgoing = PositionedTrack(9500)
            val incoming = PositionedTrack()
            cacheTrimFeatures(outgoing, incoming)
            f.play(outgoing.track)
            f.arm(incoming)
            f.captureAction()
            val action = f.captureAction()
            assertEquals("AdvanceNext", action.javaClass.simpleName)
            f.execute(action)
            assertSame(incoming.track, f.playing)
            assertEquals(800L, incoming.position, "TrackStart must not rewind to the older 400ms trim")
            assertTrue(incoming.seeks.isNotEmpty())
            assertTrue(incoming.seeks.all { it >= 800L })
        }
    }
}
