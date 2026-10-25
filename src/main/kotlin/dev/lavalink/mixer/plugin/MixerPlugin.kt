package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NoMatchException(identifier: String) : RuntimeException("No matches for identifier: $identifier")

/**
 * Central registry for per-guild [GuildMixer]s plus the shared ~50Hz pump
 * thread that drains secondary decoders and polls crossfade triggers.
 */
@Service
class MixerPlugin(
    private val playerManager: AudioPlayerManager,
    private val config: MixerConfig,
) : DisposableBean {
    private val guilds = ConcurrentHashMap<Long, GuildMixer>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mixer-loop").apply { isDaemon = true }
    }
    /** Bounded pool: analyses decode independently per guild, so they parallelize. */
    private val analysisExecutor = Executors.newFixedThreadPool(config.analysisThreads.coerceIn(1, 8)) { r ->
        Thread(r, "mixer-analysis").apply { isDaemon = true }
    }
    private var tick: Long = 0

    init {
        scheduler.scheduleAtFixedRate(::loop, 0, 20, TimeUnit.MILLISECONDS)
    }

    fun getOrCreate(guildId: Long): GuildMixer =
        guilds.computeIfAbsent(guildId) { GuildMixer(it, playerManager, config, analysisExecutor) }

    fun find(guildId: Long): GuildMixer? = guilds[guildId]

    fun remove(guildId: Long) {
        guilds.remove(guildId)?.destroy()
    }

    /**
     * Loads the next track for gapless pre-queuing. Prefers [encodedTrack]
     * (exact, no re-resolution) over [identifier] (re-loaded via source
     * managers). The track is stored on the guild mixer when loading
     * completes; callers can join the future to report failures synchronously.
     */
    fun preloadNext(guildId: Long, identifier: String?, encodedTrack: String?): CompletableFuture<AudioTrack> {
        val future = resolveTrack(guildId, identifier, encodedTrack)
        val mixer = getOrCreate(guildId)
        mixer.queueNext(future)
        // Silence analysis runs async off the voice path; results apply if
        // ready before the track starts, otherwise they are cached by id.
        future.thenAcceptAsync({ track ->
            try {
                mixer.analyzeBlocking(track)
            } catch (e: Exception) {
                log.warn("silence analysis failed on guild {}", guildId, e)
            }
        }, analysisExecutor)
        return future
    }

    /**
     * Queues a ducked overlay announcement; it starts automatically once the
     * secondary player is free (no overlay, no overlap in flight). Successive
     * announcements play back to back in enqueue order.
     */
    fun announce(
        guildId: Long,
        identifier: String?,
        encodedTrack: String?,
        duck: Float?,
        mode: GuildMixer.AnnounceMode = GuildMixer.AnnounceMode.SEQUENTIAL,
    ): Pair<Long, Int> {
        val mixer = getOrCreate(guildId)
        return mixer.enqueueAnnouncement(identifier, encodedTrack, duck ?: mixer.duckLevel, mode)
    }

    private fun resolveTrack(guildId: Long, identifier: String?, encodedTrack: String?): CompletableFuture<AudioTrack> {
        if (!encodedTrack.isNullOrBlank()) {
            return try {
                CompletableFuture.completedFuture(decodeTrack(encodedTrack))
            } catch (e: Exception) {
                CompletableFuture.failedFuture(e)
            }
        }
        if (!identifier.isNullOrBlank()) return loadTrack(guildId, identifier)
        return CompletableFuture.failedFuture(IllegalArgumentException("Provide identifier or encodedTrack"))
    }

    /** Decodes a Lavalink base64 track locally — no network, no re-resolution. */
    fun decodeTrack(encoded: String): AudioTrack {
        val bytes = Base64.getDecoder().decode(encoded.trim())
        ByteArrayInputStream(bytes).use { stream ->
            return playerManager.decodeTrack(MessageInput(stream)).decodedTrack
        }
    }

    fun loadTrack(orderKey: Any, identifier: String): CompletableFuture<AudioTrack> {
        val future = CompletableFuture<AudioTrack>()
        playerManager.loadItemOrdered(orderKey, identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                val track = playlist.selectedTrack ?: playlist.tracks.firstOrNull()
                if (track != null) future.complete(track)
                else future.completeExceptionally(NoMatchException(identifier))
            }

            override fun noMatches() {
                future.completeExceptionally(NoMatchException(identifier))
            }

            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }
        })
        return future
    }

    private fun loop() {
        tick++
        val poll = tick % 5L == 0L
        for (mixer in guilds.values) {
            try {
                mixer.drain()
            } catch (e: Exception) {
                log.warn("mixer drain failed", e)
            }
            if (poll) {
                try {
                    mixer.poll()
                } catch (e: Exception) {
                    log.warn("mixer poll failed", e)
                }
            }
            retryAnalysis(mixer)
            maybeStartAnnouncement(mixer)
        }
    }

    /** Preload-time analysis skipped on a busy sub player resumes once it frees up. */
    private fun retryAnalysis(mixer: GuildMixer) {
        val clone = try {
            mixer.maybeRetryAnalysis()
        } catch (e: Exception) {
            log.warn("analysis retry check failed on guild {}", mixer.guildId, e)
            return
        } ?: return
        analysisExecutor.execute {
            try {
                mixer.analyzeBlocking(clone)
            } catch (e: Exception) {
                log.warn("silence analysis retry failed on guild {}", mixer.guildId, e)
            }
        }
    }

    /**
     * Starts the next queued announcement when the secondary player is free.
     * The track is resolved at start time so queued TTS URLs stay fresh; a
     * busy-sub race during resolution re-queues the item at the head.
     */
    private fun maybeStartAnnouncement(mixer: GuildMixer) {
        val item = mixer.takeNextAnnouncement() ?: return
        resolveTrack(mixer.guildId, item.identifier, item.encodedTrack).whenComplete { track, err ->
            if (err != null || track == null) {
                mixer.onAnnouncementFailed(item, err ?: IllegalArgumentException("announcement track resolved to nothing"))
                return@whenComplete
            }
            try {
                mixer.startOverlay(track, item.duckLevel, item.id, item.mode)
                mixer.onAnnouncementStarted(item)
            } catch (e: Exception) {
                mixer.requeueAnnouncement(item, e)
            }
        }
    }

    override fun destroy() {
        scheduler.shutdownNow()
        analysisExecutor.shutdownNow()
        guilds.values.forEach {
            try {
                it.destroy()
            } catch (e: Exception) {
                log.warn("mixer destroy failed", e)
            }
        }
        guilds.clear()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixerPlugin::class.java)
    }
}
