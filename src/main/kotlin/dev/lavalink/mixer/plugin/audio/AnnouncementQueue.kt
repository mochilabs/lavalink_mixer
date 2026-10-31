package dev.lavalink.mixer.plugin

import org.slf4j.LoggerFactory

data class AnnouncementItem(
    val id: Long,
    val identifier: String?,
    val encodedTrack: String?,
    val duckLevel: Float,
    val mode: AnnounceMode,
)

enum class AnnounceMode { SEQUENTIAL, PARALLEL }

/**
 * Per-guild FIFO queue for TTS/announcement overlays.
 *
 * Callers synchronize on [GuildMixer]'s lock when mutating or inspecting this
 * queue alongside overlay/crossfade state.
 */
internal class AnnouncementQueue(
    private val guildId: Long,
    private val activeParallelCount: () -> Int,
    private val maxParallelOverlays: () -> Int,
    private val isSequentialBusy: () -> Boolean,
) {
    private val queue = ArrayDeque<AnnouncementItem>()
    private var seq: Long = 0L

    /** A queued item is being resolved/started on the plugin's thread; prevents double starts. */
    private var resolving: Boolean = false

    val size: Int
        get() = queue.size

    fun nextId(): Long = ++seq

    fun enqueue(
        identifier: String?,
        encodedTrack: String?,
        duckLevel: Float,
        mode: AnnounceMode,
    ): Pair<Long, Int> {
        val item = AnnouncementItem(++seq, identifier, encodedTrack, duckLevel, mode)
        queue.addLast(item)
        log.info("queued announcement {} on guild {} ({} waiting)", item.id, guildId, queue.size)
        return item.id to queue.size
    }

    fun takeNext(): AnnouncementItem? {
        if (resolving) return null
        if (activeParallelCount() < maxParallelOverlays()) {
            val idx = queue.indexOfFirst { it.mode == AnnounceMode.PARALLEL }
            if (idx >= 0) {
                val item = queue.removeAt(idx)
                resolving = true
                return item
            }
        }
        if (isSequentialBusy()) return null
        val item = queue.firstOrNull() ?: return null
        if (item.mode == AnnounceMode.PARALLEL) return null
        queue.removeFirst()
        resolving = true
        return item
    }

    fun requeue(item: AnnouncementItem) {
        queue.addFirst(item)
        resolving = false
    }

    fun markFinishedResolution() {
        resolving = false
    }

    fun clear(): Int {
        val dropped = queue.size
        queue.clear()
        if (dropped > 0) {
            log.info("cleared {} queued announcement(s) on guild {}", dropped, guildId)
        }
        return dropped
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AnnouncementQueue::class.java)
    }
}
