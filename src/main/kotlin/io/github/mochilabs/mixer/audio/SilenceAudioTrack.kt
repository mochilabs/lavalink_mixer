package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor

/**
 * Endless stereo silence that the mixer plays on an otherwise idle main player.
 *
 * Lavaplayer only builds (and runs) the filter chain while a track plays, so an
 * announcement sent to an idle guild has nowhere to be mixed. This carrier gives
 * [MixerFilter] chunks to process; the overlay PCM is added to them like to any
 * other main track.
 *
 * It is paced to real time with a small lead. A normal decoder free-runs up to a
 * full frame buffer ahead, which would push the announcement's secondary PCM
 * through the filter faster than it is decoded and zero-fill most of it.
 *
 * Lavalink forwards TrackStart/TrackEnd of every track to the client, and a
 * plugin cannot suppress them. Clients tell the carrier apart by
 * [IDENTIFIER] / its `mixer://idle-carrier` URI / [isCarrier]; the mixer always ends it with
 * `stopTrack()`, so the end reason is `stopped` rather than `finished`.
 */
class SilenceAudioTrack : BaseAudioTrack(INFO) {
    override fun process(executor: LocalAudioTrackExecutor) {
        val pipeline = AudioPipelineFactory.create(executor.processingContext, PcmFormat(CHANNELS, SAMPLE_RATE))
        try {
            val chunk = ShortArray(CHUNK_FRAMES * CHANNELS)
            val startNanos = System.nanoTime()
            var produced = 0L
            // One performRead covers the whole track (lavaplayer repeats it only after
            // a seek), so loop here; stopping interrupts the sleep or pipeline call.
            executor.executeProcessingLoop({
                while (true) {
                    pipeline.process(chunk, 0, chunk.size)
                    produced += CHUNK_FRAMES
                    val dueNanos = startNanos + produced * 1_000_000_000L / SAMPLE_RATE - LEAD_NANOS
                    val waitNanos = dueNanos - System.nanoTime()
                    if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                }
            }, null)
        } finally {
            pipeline.close()
        }
    }

    override fun makeShallowClone(): AudioTrack = SilenceAudioTrack()

    /** Lavalink encodes every track it reports to the client, which needs a source manager. */
    override fun getSourceManager(): AudioSourceManager = SilenceSourceManager

    companion object {
        const val IDENTIFIER = "lavalink-mixer:idle-carrier"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val CHUNK_FRAMES = 960 // 20 ms
        private const val LEAD_NANOS = 100_000_000L
        private val INFO = AudioTrackInfo(
            "mixer idle announcement carrier", "lavalink-mixer", Long.MAX_VALUE,
            IDENTIFIER, true, "mixer://idle-carrier", null, null,
        )

        fun isCarrier(track: AudioTrack?): Boolean = track is SilenceAudioTrack
    }
}

/** Encodes and decodes the carrier only; it never resolves identifiers. */
object SilenceSourceManager : AudioSourceManager {
    override fun getSourceName(): String = "mixer-idle-carrier"
    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? = null
    override fun isTrackEncodable(track: AudioTrack): Boolean = true
    override fun encodeTrack(track: AudioTrack, output: java.io.DataOutput) = Unit
    override fun decodeTrack(trackInfo: AudioTrackInfo, input: java.io.DataInput): AudioTrack = SilenceAudioTrack()
    override fun shutdown() = Unit
}
