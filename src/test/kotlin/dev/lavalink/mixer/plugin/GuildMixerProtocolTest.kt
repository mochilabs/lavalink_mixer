package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GuildMixerProtocolTest {
    private val sampleRate = 48000
    private lateinit var manager: DefaultAudioPlayerManager
    private lateinit var testFile: File
    private lateinit var mixer: GuildMixer

    @BeforeEach
    fun setup() {
        manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(LocalAudioSourceManager())
        manager.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE

        testFile = toneFile("mixer-protocol-test", 440.0, 5)
        val config = MixerConfig().apply {
            crossfadeEnabled = true
            crossfadeMs = 3000
        }
        mixer = GuildMixer(9999L, manager, config, java.util.concurrent.Executor { it.run() })
    }

    @AfterEach
    fun teardown() {
        mixer.destroy()
        testFile.delete()
    }

    @Test
    fun `reset sets lastKnownEpoch and resets request cache`() {
        val key = UUID.randomUUID().toString()
        val res = mixer.reset(42L, key, windowOpenGraceMs = 0L)
        assertIs<ResetResult.Success>(res)
        assertEquals(42L, res.lastKnownEpoch)
        assertEquals(42L, mixer.lastKnownEpoch)
    }

    @Test
    fun `idempotency key prevents duplicate execution`() {
        mixer.reset(10L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
        val key = UUID.randomUUID().toString()

        val first = mixer.arm(10L, key, null, true)
        assertIs<ArmResult.Success>(first)

        val second = mixer.arm(10L, key, null, true)
        assertIs<ArmResult.Duplicate>(second)
    }

    @Test
    fun `stale epoch requests are rejected with 409 status`() {
        mixer.reset(20L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)

        // Sending epoch 15 when lastKnown is 20
        val key = UUID.randomUUID().toString()
        val res = mixer.arm(15L, key, null, true)
        assertIs<ArmResult.Stale>(res)
        assertEquals(20L, res.lastKnown)
    }

    @Test
    fun `pre-reset window arrival is rejected`() {
        // Window open in 1500ms
        mixer.reset(20L, UUID.randomUUID().toString(), windowOpenGraceMs = 1500L)

        val key = UUID.randomUUID().toString()
        val res = mixer.arm(20L, key, null, true)
        assertIs<ArmResult.PreResetArrival>(res)
    }

    @Test
    fun `override validates action and track existence before gate`() {
        mixer.reset(10L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
        val key = UUID.randomUUID().toString()

        // Invalid action should fail with BadRequest
        val badAction = mixer.override(10L, key, "INVALID", null)
        assertIs<OverrideResult.BadRequest>(badAction)

        // PLAY_NOW without track should fail with BadRequest
        val nullTrack = mixer.override(10L, key, "PLAY_NOW", null)
        assertIs<OverrideResult.BadRequest>(nullTrack)

        // Form validation failure must not consume the Idempotency-Key
        val validStop = mixer.override(10L, key, "STOP", null)
        assertIs<OverrideResult.Success>(validStop)
    }

    @Test
    fun `invalidate clears armed slot and transitions to PLAYING`() {
        mixer.reset(10L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
        val track = loadTrack(testFile)

        val arm = mixer.arm(10L, UUID.randomUUID().toString(), track, false)
        assertIs<ArmResult.Success>(arm)
        assertEquals(PlaybackState.ARMED, mixer.playbackState)

        val inv = mixer.invalidate(11L, UUID.randomUUID().toString())
        assertIs<InvalidateResult.Success>(inv)
        assertEquals(PlaybackState.PLAYING, mixer.playbackState)
        assertEquals(11L, mixer.lastKnownEpoch)
    }

    @Test
    fun `pre-reset arrival includes windowOpenAt and lastKnownEpoch and reset and invalidate tear down active xfade`() {
        mixer.reset(50L, UUID.randomUUID().toString(), windowOpenGraceMs = 1500L)
        val preReset = mixer.arm(50L, UUID.randomUUID().toString(), null, true)
        assertIs<ArmResult.PreResetArrival>(preReset)
        assertEquals(50L, preReset.lastKnownEpoch)
        kotlin.test.assertTrue(preReset.windowOpenAt > System.currentTimeMillis() - 100L)

        // Reset with 0ms grace and verify active crossfade is torn down by invalidate and reset
        mixer.reset(60L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
        mixer.beginXfadeForTest(48000)
        kotlin.test.assertTrue(mixer.isXfadeActive())
        mixer.invalidate(61L, UUID.randomUUID().toString())
        kotlin.test.assertFalse(mixer.isXfadeActive())

        mixer.beginXfadeForTest(48000)
        kotlin.test.assertTrue(mixer.isXfadeActive())
        mixer.reset(70L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
        kotlin.test.assertFalse(mixer.isXfadeActive())
    }

    @Test
    fun `encodeTrackBase64 normalizes non-zero position so handoff seek preserves canonical encodedTrack`() {
        val original = loadTrack(testFile)
        val initialEncoded = mixer.encodeTrackBase64(original)

        val bytes = java.util.Base64.getDecoder().decode(initialEncoded)
        val decoded = manager.decodeTrack(com.sedmelluq.discord.lavaplayer.tools.io.MessageInput(java.io.ByteArrayInputStream(bytes))).decodedTrack
        val clone = decoded.makeClone().apply { position = 3000L }

        assertEquals(initialEncoded, mixer.encodeTrackBase64(clone))
    }

    @Test
    fun `arm tears down active crossfade and validates non-empty null track before gate`() {
        mixer.reset(10L, UUID.randomUUID().toString(), windowOpenGraceMs = 0L)
        val trackB = loadTrack(testFile)
        val trackC = loadTrack(testFile)

        // Non-empty arm with null track must fail with BadRequest and not consume key
        val reusableKey = UUID.randomUUID().toString()
        val badArm = mixer.arm(10L, reusableKey, null, false)
        assertIs<ArmResult.BadRequest>(badArm)

        val goodArm = mixer.arm(10L, reusableKey, trackB, false)
        assertIs<ArmResult.Success>(goodArm)

        // Active overlap must be aborted when client re-arms with another track or empty
        mixer.beginXfadeForTest(48000)
        kotlin.test.assertTrue(mixer.isXfadeActive())
        val reArm = mixer.arm(11L, UUID.randomUUID().toString(), trackC, false)
        assertIs<ArmResult.Success>(reArm)
        kotlin.test.assertFalse(mixer.isXfadeActive())

        mixer.beginXfadeForTest(48000)
        kotlin.test.assertTrue(mixer.isXfadeActive())
        val emptyArm = mixer.arm(12L, UUID.randomUUID().toString(), null, true)
        assertIs<ArmResult.Success>(emptyArm)
        kotlin.test.assertFalse(mixer.isXfadeActive())
        assertEquals(PlaybackState.NO_NEXT, mixer.playbackState)
    }

    @Test
    fun `invalidate and reset enforce idempotency payload mismatch`() {
        val resetKey = UUID.randomUUID().toString()
        val r1 = mixer.reset(10L, resetKey, windowOpenGraceMs = 0L)
        assertIs<ResetResult.Success>(r1)
        val rDup = mixer.reset(10L, resetKey, windowOpenGraceMs = 0L)
        assertIs<ResetResult.Duplicate>(rDup)
        val rMismatch = mixer.reset(20L, resetKey, windowOpenGraceMs = 0L)
        assertIs<ResetResult.PayloadMismatch>(rMismatch)
        assertEquals(10L, mixer.lastKnownEpoch)

        val armKey = UUID.randomUUID().toString()
        val track = loadTrack(testFile)
        assertIs<ArmResult.Success>(mixer.arm(10L, armKey, track, false))
        assertEquals(PlaybackState.ARMED, mixer.playbackState)

        // Reusing arm's Idempotency-Key on invalidate must return PayloadMismatch without clearing slot or changing epoch
        val invMismatch = mixer.invalidate(99L, armKey)
        assertIs<InvalidateResult.PayloadMismatch>(invMismatch)
        assertEquals(10L, mixer.lastKnownEpoch)
        assertEquals(PlaybackState.ARMED, mixer.playbackState)
    }

    private fun loadTrack(file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItemOrdered(file.absolutePath, file.absolutePath, object : com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: com.sedmelluq.discord.lavaplayer.track.AudioPlaylist) { future.complete(playlist.tracks[0]) }
            override fun noMatches() { future.completeExceptionally(RuntimeException("no matches")) }
            override fun loadFailed(e: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) { future.completeExceptionally(e) }
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun toneFile(prefix: String, frequency: Double, seconds: Int): File {
        val file = File.createTempFile(prefix, ".wav")
        val samples = sampleRate * seconds
        val dataLen = samples * 2 * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(2)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2 * 2)
            out.writeShortLE(4)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2.0 * PI * frequency * i / sampleRate) * 16000).toInt()
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
