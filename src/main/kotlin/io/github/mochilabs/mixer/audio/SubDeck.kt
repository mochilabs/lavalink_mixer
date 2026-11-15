package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * The guild's single secondary deck: one player whose PCM is siphoned into the
 * main chain, plus the two gains that mix it against the main track.
 *
 * A crossfade and a sequential overlay both borrow it, never at the same time.
 * Its mutable fields are guarded by the guild lock; the gains are advanced by
 * the elected feeder on the voice thread.
 */
internal class SubDeck(
    private val guildId: Long,
    private val playerManager: AudioPlayerManager,
) {
    var player: AudioPlayer? = null
    var factory: SiphonFactory? = null
        private set
    var track: AudioTrack? = null
    /** Wall-clock time the deck was last armed; 0 before the first use. */
    var startMs: Long = 0L
    /** Bumped on every sub arming/teardown; in-flight startOverlap/startOverlay re-check it. */
    var gen: Long = 0L

    val mainGain = GainRamp(1f)
    val subGain = GainRamp(0f)

    val siphon = SiphonQueue.forFrameBuffer(frameBufferDurationMs())
    val drainFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    /**
     * How much decoded audio Lavaplayer pre-buffers into a track's frame
     * buffer before anyone pulls a frame. The siphon has to be able to hold
     * all of it, or the first seconds of an overlaid track are dropped the
     * instant playTrack() returns. Guarded because tests build mixers over
     * stub managers.
     */
    fun frameBufferDurationMs(): Int = try {
        playerManager.frameBufferDuration
    } catch (t: Throwable) {
        DEFAULT_FRAME_BUFFER_MS
    }

    /**
     * A provide() frame must hold one whole encoded chunk of the *manager's*
     * output format. Sizing it from DISCORD_OPUS while the server emits PCM
     * overflows on every frame, [GuildMixer.drain] then logs and drops each attempt, the
     * secondary decoder never advances — and the crossfade degenerates into
     * "the old track plays out, the new one starts after". Also guarded:
     * tests build mixers over stub managers.
     */
    private fun provideBufferSize(): Int = try {
        playerManager.configuration?.outputFormat?.maximumChunkSize()
            ?: StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    } catch (t: Throwable) {
        StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    }

    /** Allocates a separate buffer while pre-filling the secondary decoder. */
    private fun newProvideFrame(): MutableAudioFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    /** Pulls a decoded frame from the live secondary player during prefill. */
    private fun pumpFrame(sub: AudioPlayer, frame: MutableAudioFrame): Boolean = try {
        sub.provide(frame)
    } catch (e: Exception) {
        log.warn("secondary decoder prefill pump failed on guild {}", guildId, e)
        false
    }


    /** Returns the secondary player, creating it with [listener] on first use. */
    fun ensureLocked(listener: AudioEventListener): AudioPlayer {
        var sub = player
        if (sub == null) {
            sub = playerManager.createPlayer()
            val factory = SiphonFactory(siphon, enableTimescale = true)
            this.factory = factory
            sub.setFilterFactory(factory)
            sub.addListener(listener)
            player = sub
        }
        return sub
    }

    /** Waits for one second of PCM, or a fully decoded short track, before fading. */
    fun prefill(sub: AudioPlayer, current: () -> Boolean): Boolean {
        val frame = newProvideFrame()
        try {
            var burst = 0
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (burst < 600 && System.nanoTime() < deadline && current()) {
                if (siphon.pendingSamples >= SAMPLE_RATE) return true
                if (sub.playingTrack == null) return siphon.pendingSamples > 0
                if (pumpFrame(sub, frame)) burst++ else Thread.sleep(5)
            }
            return siphon.pendingSamples >= SAMPLE_RATE && current()
        } finally {
            frame.setBuffer(java.nio.ByteBuffer.allocate(0))
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SubDeck::class.java)
        private const val SAMPLE_RATE = 48000
        private const val DEFAULT_FRAME_BUFFER_MS = 5000
    }
}
