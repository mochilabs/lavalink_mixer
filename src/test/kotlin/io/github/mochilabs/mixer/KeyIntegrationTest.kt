package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyIntegrationTest {
    @Test
    fun `key only decoding exposes G minor and caches unknown without retrying or arming warmups`() {
        val manager = DefaultAudioPlayerManager().apply {
            registerSourceManager(LocalAudioSourceManager())
            configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        }
        val mixer = GuildMixer(774L, manager, MixerConfig().apply {
            keyDetectionEnabled = true
            keyScanMs = 5000
        }, java.util.concurrent.Executor { it.run() })
        val file = wav(keyTone(7, true, 6 * 48000))
        val silence = wav(FloatArray(6 * 48000))
        try {
            val track = load(manager, file)
            mixer.analyzeBlocking(track, cacheOnly = true)
            assertFalse(mixer.hasNext(), "a key warmup must not arm playback")
            assertFalse(mixer.requestPreanalysis(track), "a completed key must not decode again")
            assertNull(AnalysisCaches.bpm[track.identifier], "key-only mode must not enable BPM")
            assertNull(AnalysisCaches.norm[track.identifier])
            mixer.queueNext(CompletableFuture.completedFuture(track))
            val result = assertNotNull(mixer.peekNextKey())
            assertEquals(track.info.identifier, result.identifier)
            assertEquals(5000L, result.analysis.windowEndMs)
            assertEquals(KeyMode.MINOR, assertIs<KeyEstimate.Candidate>(result.analysis.estimate).mode)
            assertEquals(7, assertIs<KeyEstimate.Candidate>(result.analysis.estimate).pitchClass)
            assertNull(mixer.maybeRetryAnalysis())
            mixer.updateKey(false)
            assertNull(mixer.peekNextKey())
            mixer.updateKey(true)
            val quiet = load(manager, silence)
            mixer.queueNext(CompletableFuture.completedFuture(quiet))
            mixer.analyzeBlocking(quiet)
            assertEquals("silence", assertIs<KeyEstimate.Unknown>(assertNotNull(mixer.peekNextKey()).analysis.estimate).reason)
            assertNull(mixer.maybeRetryAnalysis(), "unknown is a completed result")
        } finally {
            mixer.destroy()
            manager.shutdown()
            file.delete()
            silence.delete()
        }
    }

    @Test
    fun `REST advertises key support toggles independently and serializes estimated and unknown variants`() {
        val manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(LocalAudioSourceManager())
        val plugin = MixerPlugin(manager, MixerConfig())
        val file = wav(FloatArray(48000))
        try {
            val handler = MixerRestHandler(plugin)
            assertTrue(handler.info().body!!.keyDetectionSupported)
            val enabled = handler.key(KeyRequest("542599372836438016", true, 1)).body!!
            assertTrue(enabled.keyDetectionEnabled)
            assertFalse(enabled.bpmDetectionEnabled)
            assertEquals(5000L, enabled.keyScanMs)
            assertEquals("542599372836438016", enabled.guildId)
            val state = Json.parseToJsonElement(Json.encodeToString(MixerState.serializer(), enabled)).jsonObject
            assertTrue(state.getValue("keyDetectionEnabled").jsonPrimitive.boolean)
            assertNull(enabled.currentTrackKey)
            assertNull(enabled.nextTrackKey)
            val track = load(manager, file)
            AnalysisCaches.key[track.identifier] = KeyAnalysis(0, 1000, KeyEstimate.Candidate(7, KeyMode.MINOR, 0.7f))
            plugin.getOrCreate(542599372836438016L).queueNext(CompletableFuture.completedFuture(track))
            val estimated = assertIs<TrackKeyResponse.Estimated>(handler.state(542599372836438016L).body!!.nextTrackKey)
            assertEquals("G", estimated.key)
            assertEquals("minor", estimated.scale)
            assertEquals(track.info.identifier, estimated.identifier)
            AnalysisCaches.key[track.identifier] = KeyAnalysis(0, 1000, KeyEstimate.Unknown("silence"))
            val unknownState = handler.state(542599372836438016L).body!!
            val unknown = assertIs<TrackKeyResponse.Unknown>(unknownState.nextTrackKey)
            assertEquals("silence", unknown.reason)
            val unknownWire = Json.parseToJsonElement(Json.encodeToString(MixerState.serializer(), unknownState)).jsonObject
            assertEquals("unknown", unknownWire.getValue("nextTrackKey").jsonObject.getValue("type").jsonPrimitive.content)
            assertNull(handler.key(KeyRequest("542599372836438016", false)).body!!.nextTrackKey)
            val variants = listOf<TrackKeyResponse>(
                TrackKeyResponse.Estimated("track", 0, 5000, "G", "minor", 0.7f),
                TrackKeyResponse.Unknown("track", 0, 5000, "ambiguous_key"),
            )
            for ((variant, type) in variants.zip(listOf("estimated", "unknown"))) {
                val wire = Json.parseToJsonElement(Json.encodeToString(TrackKeyResponse.serializer(), variant)).jsonObject
                assertEquals(type, wire.getValue("type").jsonPrimitive.content)
                assertEquals("track", wire.getValue("identifier").jsonPrimitive.content)
                assertEquals(variant, Json.decodeFromString(TrackKeyResponse.serializer(), wire.toString()))
            }
        } finally {
            plugin.destroy()
            manager.shutdown()
            file.delete()
        }
    }

    private fun load(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val result = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { result.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { result.completeExceptionally(IllegalStateException("playlist")) }
            override fun noMatches() { result.completeExceptionally(IllegalStateException("no match")) }
            override fun loadFailed(exception: FriendlyException) { result.completeExceptionally(exception) }
        })
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun wav(pcm: FloatArray): File {
        val file = File.createTempFile("key-analysis-", ".wav")
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun int(value: Int) { repeat(4) { out.write(value ushr (it * 8) and 255) } }
            fun short(value: Int) { repeat(2) { out.write(value ushr (it * 8) and 255) } }
            out.writeBytes("RIFF"); int(36 + pcm.size * 2); out.writeBytes("WAVEfmt ")
            int(16); short(1); short(1); int(48000); int(96000); short(2); short(16)
            out.writeBytes("data"); int(pcm.size * 2)
            pcm.forEach { short((it * 32767).toInt()) }
        }
        return file
    }
}
