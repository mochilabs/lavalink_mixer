package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides which [MixerFilter] instance feeds a guild's mixer, and watches
 * whether any filter is running in the playing track's chain at all.
 *
 * Election state is lock-free for the voice thread. The heartbeat fields are
 * guarded by the guild lock.
 */
internal class FeederElection(private val guildId: Long) {
    private val electedFeeder = AtomicLong(0L)
    private val electedSeenAtMs = AtomicLong(0L)
    private val rivalWarnAtMs = AtomicLong(0L)
    /** Test seam; production budget is ~50 voice-thread chunks of silence. */
    @Volatile var staleMs: Long = 1000L

    /** Ticks once per mixer-filter chunk; see [checkHeartbeatLocked]. */
    private val filterRunCount = AtomicLong(0L)
    private var filterRunBaseline = 0L
    private var filterBaselinePos = 0L
    @Volatile var filterMissing: Boolean = false
        private set
    private var filterMissingWarned: Boolean = false

    val filterRuns: Long get() = filterRunCount.get()

    /** Drops the current election when the main player's set of attached players changes. */
    fun reset() = electedFeeder.set(0L)

    /** Heartbeat from the voice path: the mixer filter processed a chunk. */
    fun noteFilterRun() {
        filterRunCount.incrementAndGet()
    }

    /** Whether [feederId] currently owns the feed; settle [claim] (via [GuildMixer.advance]) before reading. */
    fun isFeeder(feederId: Long): Boolean = electedFeeder.get() == feederId

    /** Called by [MixerFilter.close] when Lavaplayer tears its chain down (hot-swap, track end). */
    fun retire(feederId: Long) {
        electedFeeder.compareAndSet(feederId, 0L)
    }

    fun claim(feederId: Long): Boolean {
        while (true) {
            val elected = electedFeeder.get()
            if (elected == feederId) {
                electedSeenAtMs.set(System.currentTimeMillis())
                return true
            }
            if (elected != 0L) {
                val silentMs = System.currentTimeMillis() - electedSeenAtMs.get()
                if (silentMs < staleMs) {
                    warnRivalFeeder(feederId, elected)
                    return false
                }
                log.info(
                    "mixer feeder takeover on guild {}: incumbent filter #{} silent for {}ms, filter #{} takes over",
                    guildId, elected, silentMs, feederId,
                )
            }
            if (electedFeeder.compareAndSet(elected, feederId)) {
                electedSeenAtMs.set(System.currentTimeMillis())
                return true
            }
        }
    }

    private fun warnRivalFeeder(feederId: Long, elected: Long) {
        val now = System.currentTimeMillis()
        val last = rivalWarnAtMs.get()
        if (now - last < 60_000 || !rivalWarnAtMs.compareAndSet(last, now)) return
        log.warn(
            "second mixer filter (#{} on guild {}) is feeding a mixer owned by filter #{} — two players exist " +
                "for this guild (stale session vs fresh restore?). Only the incumbent advances the chain; " +
                "check for duplicate session players.",
            feederId, guildId, elected,
        )
    }

    fun resetHeartbeatLocked(position: Long) {
        filterRunBaseline = filterRunCount.get()
        filterBaselinePos = position
        filterMissing = false
        filterMissingWarned = false
    }

    fun checkHeartbeatLocked(attached: Boolean, audioPlayer: AudioPlayer, pos: Long) {
        val runs = filterRunCount.get()
        if (runs != filterRunBaseline) {
            if (filterMissing) {
                filterMissing = false
                log.info("mixer filter is live on guild {} ({} chunks since track start)", guildId, runs - filterRunBaseline)
            }
            return
        }
        if (filterMissingWarned || !attached || audioPlayer.isPaused) return
        val played = pos - filterBaselinePos
        if (played < 1000) return
        filterMissingWarned = true
        filterMissing = true
        log.warn(
            "mixer filter is NOT in the running track's chain on guild {}: {}ms decoded, " +
                "0 mixer filter calls. Send the `filters` op with pluginFilters.mixer BEFORE " +
                "`play` (or re-send `filters` and seek) — Lavalink does not rebuild the chain " +
                "of a track that is already playing.",
            guildId, played,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(FeederElection::class.java)
    }
}
