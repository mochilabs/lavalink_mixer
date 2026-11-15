package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** What [OverlayController] needs from its guild mixer. */
internal interface OverlayHost {
    val guildId: Long
    /** The guild state lock; every `...Locked` member here must be called while holding it. */
    val lock: Any
    /** A crossfade is running or being prepared on the deck. */
    val transitionBusyLocked: Boolean
    /** [GuildMixer.destroy] ran; no new players may be created. */
    val destroyedLocked: Boolean
    /** Listener installed on the deck's player when it is first created. */
    val subListener: AudioEventListener

    fun resetSubNormLocked()
    fun isFeeder(feederId: Long): Boolean
    fun notifyMixerEvent(type: String, body: JsonObjectBuilder.() -> Unit)
}

/**
 * Announcement queue and talk-over overlays for one guild.
 *
 * A sequential overlay borrows the [SubDeck]; parallel overlays each get their
 * own player and siphon. Both duck the music through [SubDeck.mainGain].
 */
internal class OverlayController(
    private val host: OverlayHost,
    private val settings: MixerSettings,
    private val deck: SubDeck,
    private val playerManager: AudioPlayerManager,
) {
    /** Active parallel (`mode=parallel`) overlays: each owns its decoder + siphon + fade ramp. */
    private val parallelOverlays = LinkedHashMap<Long, OverlaySlot>()
    @Volatile private var parallelOverlaySnapshot: List<OverlaySlot> = emptyList()

    private fun publishParallelOverlaysLocked() {
        parallelOverlaySnapshot = parallelOverlays.values.toList()
    }
    /** Sequential (single-sub) overlay is still active; mutually exclusive with parallel overlays. */
    private var sequentialOverlayId: Long? = null
    @Volatile var sequentialChannel: AnnounceChannel = AnnounceChannel.BOTH
        private set
    /** A sequential overlay holds the deck. The voice thread reads this without the guild lock. */
    @Volatile var overlayActive: Boolean = false
        private set

    private var announceToken: Long = 0L

    /**
     * Pairs `announceStart` and `announceEnd` for one overlay. Events are sent
     * outside [OverlayHost.lock], so an overlay that ends while its start is
     * being sent defers its end to the starting thread, keeping the order.
     * An overlay that never sent its start sends no end either.
     */
    private class AnnounceEvents {
        var sending = false
        var started = false
        var endDeferred = false
    }

    private var sequentialEvents: AnnounceEvents? = null
    private val parallelEvents = HashMap<Long, AnnounceEvents>()

    /** Called when the overlay finishes; true if this thread must send `announceEnd` now. */
    private fun endEventLocked(events: AnnounceEvents?): Boolean = when {
        events == null -> false
        events.sending -> {
            events.endDeferred = true
            false
        }
        else -> events.started
    }

    /** Called after `announceStart` was sent; true if an end arrived meanwhile and must be sent now. */
    private fun startSentLocked(events: AnnounceEvents): Boolean {
        events.sending = false
        events.started = true
        val deferred = events.endDeferred
        events.endDeferred = false
        return deferred
    }

    /** Test seam: runs after the overlay's playTrack, before the start is confirmed. */
    @Volatile internal var afterOverlayPlayForTest: ((AudioPlayer) -> Unit)? = null
    /** Test seam: runs after the start is confirmed, before `announceStart` is sent. */
    @Volatile internal var beforeAnnounceStartForTest: ((AudioPlayer) -> Unit)? = null

    val hasParallelLocked: Boolean get() = parallelOverlays.isNotEmpty()
    val queueHasWorkLocked: Boolean get() = announcementQueue.hasWork

    /** Parallel slots whose decoders the scheduler must keep draining. */
    fun drainableParallelsLocked(): List<OverlaySlot> =
        parallelOverlays.values.filter { it.active && it.player.playingTrack != null }

    /** Releases the deck flag and every parallel slot; the caller destroys the returned players. */
    fun destroyLocked(): List<OverlaySlot> {
        overlayActive = false
        sequentialEvents = null
        parallelEvents.clear()
        val p = parallelOverlays.values.toList()
        parallelOverlays.clear()
        publishParallelOverlaysLocked()
        return p
    }

    /** Listener for a single `mode=parallel` overlay slot; self-finishes on end/failure. */
    private fun parallelOverlayListener(id: Long): AudioEventAdapter = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            finishParallelOverlay(id)
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            log.warn("parallel overlay '{}' failed on guild {}: {}", titleOf(track), host.guildId, exception.toString())
            finishParallelOverlay(id)
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            log.warn("parallel overlay '{}' stuck on guild {} ({}ms)", titleOf(track), host.guildId, thresholdMs)
            finishParallelOverlay(id)
        }
    }

    /**
     * Advances the per-slot fade ramps and returns the currently active
     * parallel overlays (player + siphon + ramp) for the mixer to sum in.
     * Called on the voice thread each chunk; slots are read lock-free via
     * volatile/immutable handles, and only the ramp advances here.
     */
    fun advanceParallel(samples: Int, feederId: Long): List<OverlaySlot> {
        if (!host.isFeeder(feederId)) return emptyList()
        val snapshot = parallelOverlaySnapshot
        if (snapshot.isEmpty()) return emptyList()
        val active = snapshot.filter { it.active }
        for (slot in active) slot.ramp.advance(samples)
        return active
    }

    /** Test seam: count currently active parallel overlay slots. */
    fun parallelCountForTest(): Int = synchronized(host.lock) { parallelOverlays.size }
    fun installParallelForTest(slot: OverlaySlot) {
        synchronized(host.lock) {
            parallelOverlays[slot.id] = slot
            publishParallelOverlaysLocked()
        }
    }
    fun overlayForTest(active: Boolean, channel: AnnounceChannel = AnnounceChannel.BOTH) {
        synchronized(host.lock) {
            overlayActive = active
            sequentialChannel = channel
        }
    }

    /** Test seam: simulate [count] active parallel slots without players (for the limit check). */
    fun armParallelForTest(count: Int) {
        synchronized(host.lock) {
            parallelOverlays.clear()
            repeat(count) { i ->
                parallelOverlays[i.toLong()] = OverlaySlot(i.toLong(), playerManager.createPlayer(), SiphonQueue.forFrameBuffer(deck.frameBufferDurationMs())).apply { active = true }
            }
            publishParallelOverlaysLocked()
        }
    }

    // ---- announcement queue ----
    //
    // TTS/announcements are talk-over overlays: they duck the music rather than
    // becoming a track in the playback order. One overlay at a time is a hard
    // limit (there is a single secondary decoder), so successive announcements
    // wait here and play back to back as the secondary player frees up.


    private val announcementQueue = AnnouncementQueue(
        guildId = host.guildId,
        activeParallelCount = { parallelOverlays.size },
        maxParallelOverlays = { settings.maxParallelOverlays },
        isSequentialBusy = { overlayActive || host.transitionBusyLocked },
    )

    /** Queues an announcement; returns its id and 1-based position among queued announcements. */
    fun enqueueAnnouncement(
        identifier: String?,
        encodedTrack: String?,
        duckLevel: Float,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
        gain: Float = 1f,
    ): Pair<Long, Int> =
        synchronized(host.lock) {
            announcementQueue.enqueue(identifier, encodedTrack, duckLevel, mode, channel, GuildMixer.sanitizeAnnounceGain(gain))
        }

    fun takeNextAnnouncement(): AnnouncementItem? = synchronized(host.lock) {
        announcementQueue.takeNext()
    }

    /** Puts an item back at the head after a busy race or transient failure. */
    fun requeueAnnouncement(item: AnnouncementItem, cause: Throwable?) {
        synchronized(host.lock) {
            announcementQueue.requeue(item)
        }
        log.warn("announcement {} re-queued on guild {}: {}", item.id, host.guildId, cause?.toString() ?: "secondary player busy")
    }

    /** Drops an item whose track could not be resolved/started. */
    fun onAnnouncementFailed(item: AnnouncementItem, cause: Throwable?) {
        synchronized(host.lock) { announcementQueue.markFinishedResolution() }
        log.warn("announcement {} on guild {} dropped: {}", item.id, host.guildId, cause?.toString() ?: "unknown")
    }

    fun onAnnouncementStarted(item: AnnouncementItem) {
        synchronized(host.lock) { announcementQueue.markFinishedResolution() }
    }

    fun announcementQueueSize(): Int = synchronized(host.lock) { announcementQueue.size }

    /** Removes all queued (not active) announcements; returns how many were dropped. */
    fun clearAnnouncements(): Int = synchronized(host.lock) {
        announcementQueue.clear()
    }

    fun startOverlay(
        track: AudioTrack,
        duck: Float,
        announcementId: Long? = null,
        mode: AnnounceMode = AnnounceMode.SEQUENTIAL,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
        gain: Float = 1f,
    ): AudioTrack {
        val level = GuildMixer.sanitizeAnnounceGain(gain)
        if (mode == AnnounceMode.PARALLEL) {
            startParallelOverlay(track, duck, announcementId, channel, level)
            return track
        }
        val (sub, gen) = synchronized(host.lock) {
            if (host.destroyedLocked) throw MixerDestroyedException("mixer destroyed on guild ${host.guildId}")
            if (overlayActive || host.transitionBusyLocked) throw MixerBusyException("secondary player busy on guild ${host.guildId}")
            val s = deck.ensureLocked(host.subListener)
            deck.factory?.resetSpeed()
            announceToken++
            deck.track = track
            overlayActive = true
            sequentialEvents = AnnounceEvents()
            sequentialOverlayId = announcementId
            sequentialChannel = channel
            deck.startMs = System.currentTimeMillis()
            deck.siphon.clear()
            // Overlays are announcements: they use their original level, not
            // the outgoing crossfade track's normalization gain.
            host.resetSubNormLocked()
            deck.mainGain.rampTo(duck.coerceIn(0f, 1f), GuildMixer.msToSamples(settings.duckFadeMs))
            deck.subGain.setNow(0f)
            deck.subGain.rampTo(level, GuildMixer.msToSamples(150))
            s to ++deck.gen
        }
        try {
            sub.playTrack(track)
        } catch (e: Exception) {
            log.warn("overlay play failed on guild {}", host.guildId, e)
            finishOverlay()
            throw e
        }
        afterOverlayPlayForTest?.invoke(sub)
        val events = synchronized(host.lock) {
            val ev = sequentialEvents
            if (deck.gen == gen && overlayActive && deck.track === track && ev != null) ev.also { it.sending = true } else null
        }
        if (events == null) {
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("superseded overlay stop failed on guild {}", host.guildId, e)
            }
            deck.siphon.clear()
            if (synchronized(host.lock) { overlayActive && deck.track === track }) {
                finishOverlay()
            }
            throw OverlayStartAbortedException("overlay ended or was cancelled during start on guild ${host.guildId}")
        }
        beforeAnnounceStartForTest?.invoke(sub)
        log.info("overlay '{}' started on guild {}", track.info.title, host.guildId)
        host.notifyMixerEvent("announceStart") {
            if (announcementId != null) put("announcementId", announcementId)
            putTrack(track)
        }
        if (synchronized(host.lock) { startSentLocked(events) }) host.notifyMixerEvent("announceEnd") {}
        return track
    }

    /**
     * Starts a `mode=parallel` overlay on its own secondary player, mixed
     * into the main chain alongside (but independent of) the crossfade sub.
     * Up to [MixerSettings.maxParallelOverlays] run at once; music ducking is handled by
     * the shared [SubDeck.mainGain], so the first parallel overlay ducks and the
     * last one to finish releases it.
     */
    private fun startParallelOverlay(
        track: AudioTrack,
        duck: Float,
        announcementId: Long?,
        channel: AnnounceChannel = AnnounceChannel.BOTH,
        level: Float = 1f,
    ) {
        val slot = synchronized(host.lock) {
            if (host.destroyedLocked) throw MixerDestroyedException("mixer destroyed on guild ${host.guildId}")
            if (parallelOverlays.size >= settings.maxParallelOverlays) {
                throw MixerBusyException("parallel overlay limit (${settings.maxParallelOverlays}) reached on guild ${host.guildId}")
            }
            val id = announcementId ?: announcementQueue.nextId()
            val player = playerManager.createPlayer()
            val siphon = SiphonQueue.forFrameBuffer(deck.frameBufferDurationMs())
            player.setFilterFactory(SiphonFactory(siphon))
            player.addListener(parallelOverlayListener(id))
            val s = OverlaySlot(id, player, siphon, channel)
            parallelOverlays[id] = s
            parallelEvents[id] = AnnounceEvents()
            s.track = track
            s.active = true
            s.ramp.setNow(0f)
            s.ramp.rampTo(level, GuildMixer.msToSamples(150))
            publishParallelOverlaysLocked()
            // First parallel overlay (or a parallel overlay while music is
            // not otherwise ducked) ducks the music.
            deck.mainGain.rampTo(duck.coerceIn(0f, 1f), GuildMixer.msToSamples(settings.duckFadeMs))
            s
        }
        try {
            slot.player.playTrack(track)
        } catch (e: Exception) {
            log.warn("parallel overlay play failed on guild {}", host.guildId, e)
            finishParallelOverlay(slot.id)
            throw e
        }
        // destroy() between the slot's creation and playTrack already destroyed this player;
        // playTrack would have restarted it with nobody left to stop it.
        if (synchronized(host.lock) { host.destroyedLocked }) {
            runCatching { slot.player.destroy() }
            throw MixerDestroyedException("mixer destroyed while starting a parallel overlay on guild ${host.guildId}")
        }
        afterOverlayPlayForTest?.invoke(slot.player)
        // If the slot already finished (its track ended or failed at once), it
        // never started: like a sequential overlay, it sends neither event.
        val events = synchronized(host.lock) {
            val ev = parallelEvents[slot.id]
            if (parallelOverlays[slot.id] === slot && ev != null) ev.also { it.sending = true } else null
        } ?: throw OverlayStartAbortedException("parallel overlay ended during start on guild ${host.guildId}")
        beforeAnnounceStartForTest?.invoke(slot.player)
        log.info("parallel overlay '{}' (id {}) started on guild {}", track.info.title, slot.id, host.guildId)
        host.notifyMixerEvent("announceStart") {
            put("announcementId", slot.id)
            put("mode", "parallel")
            putTrack(track)
        }
        if (synchronized(host.lock) { startSentLocked(events) }) {
            host.notifyMixerEvent("announceEnd") { put("announcementId", slot.id) }
        }
    }

    fun cancelOverlay(): Boolean {
        val sub = synchronized(host.lock) {
            if (!overlayActive) return false
            deck.gen++
            deck.player
        }
        try {
            sub?.stopTrack()
        } finally {
            finishOverlay()
        }
        return true
    }

    fun finishOverlay() {
        val (sub, sendEnd) = synchronized(host.lock) {
            if (!overlayActive) return
            overlayActive = false
            val sendEnd = endEventLocked(sequentialEvents)
            sequentialEvents = null
            deck.track = null
            sequentialOverlayId = null
            sequentialChannel = AnnounceChannel.BOTH
            host.resetSubNormLocked()
            deck.siphon.clear()
            // Release the music duck only if no parallel overlays are still
            // holding it; otherwise the last parallel overlay will release it.
            if (parallelOverlays.isEmpty()) {
                deck.mainGain.rampTo(1f, GuildMixer.msToSamples(settings.duckFadeMs))
            }
            deck.subGain.setNow(0f)
            deck.player to sendEnd
        }
        log.info("overlay finished on guild {}", host.guildId)
        if (sendEnd) host.notifyMixerEvent("announceEnd") {}
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", host.guildId, e)
        }
    }

    /** Tears down a `mode=parallel` overlay slot and releases the duck if it was the last. */
    private fun finishParallelOverlay(id: Long) {
        val (slot, sendEnd) = synchronized(host.lock) {
            val removed = parallelOverlays.remove(id) ?: return
            removed.active = false
            publishParallelOverlaysLocked()
            removed to endEventLocked(parallelEvents.remove(id))
        }
        log.info("parallel overlay (id {}) finished on guild {}", id, host.guildId)
        if (sendEnd) host.notifyMixerEvent("announceEnd") { put("announcementId", id) }
        try {
            slot.player.stopTrack()
        } catch (e: Exception) {
            log.warn("parallel overlay stop failed on guild {}", host.guildId, e)
        }
        synchronized(host.lock) {
            // Release the duck once no overlay (sequential or parallel) holds it.
            if (!overlayActive && parallelOverlays.isEmpty()) {
                deck.mainGain.rampTo(1f, GuildMixer.msToSamples(settings.duckFadeMs))
            }
        }
        try {
            slot.player.destroy()
        } catch (e: Exception) {
            log.warn("parallel overlay player destroy failed on guild {}", host.guildId, e)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OverlayController::class.java)
    }
}
