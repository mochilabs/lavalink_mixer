package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.log10

@Serializable
data class PluginInfoResponse(val protocolVersion: Int, val version: String)

@Serializable
data class ArmRequest(
    val guildId: String,
    val epoch: Long,
    val encodedTrack: String? = null,
    val isEmpty: Boolean = false,
)

@Serializable
data class InvalidateRequest(val guildId: String, val epoch: Long)

@Serializable
data class OverrideRequest(
    val guildId: String,
    val epoch: Long,
    val action: String,
    val encodedTrack: String? = null,
)

@Serializable
data class ResetRequest(val guildId: String, val epoch: Long)
@Serializable
data class ConflictErrorResponse(val error: String, val lastKnownEpoch: Long)

@Serializable
data class V2StateResponse(
    val guildId: String,
    val protocolVersion: Int,
    val state: String,
    val lastKnownEpoch: Long,
    val crossfadeEnabled: Boolean,
    val crossfadeMs: Long,
)

@Serializable
data class QueueRequest(
    /** Snowflake as a string — JSON numbers lose precision past 2^53. */
    val guildId: String,
    /** Re-resolved via source managers. */
    val identifier: String? = null,
    /** Exact Lavalink base64 track — preferred, no re-resolution. At least one of the two is required. */
    val encodedTrack: String? = null,
)

@Serializable
data class CrossfadeRequest(val guildId: String, val enabled: Boolean, val durationMs: Long? = null)

@Serializable
data class FadeInRequest(val guildId: String, val durationMs: Long)

@Serializable
data class AnnounceRequest(
    val guildId: String,
    val identifier: String? = null,
    val encodedTrack: String? = null,
    val duckLevel: Float? = null,
    /** `sequential` (default, plays after the current overlay finishes) or `parallel` (plays concurrently, up to maxParallelOverlays). */
    val mode: String? = null,
)

@Serializable
data class AnnounceCancelRequest(val guildId: String)

@Serializable
data class AnnounceQueuedResponse(
    val guildId: String,
    val id: Long,
    /** 1-based position among queued announcements at enqueue time. */
    val position: Int,
    val queueLength: Int,
)

@Serializable
data class NormalizationRequest(val guildId: String, val enabled: Boolean, val targetLufs: Float? = null)

@Serializable
data class SilenceRequest(
    val guildId: String,
    val enabled: Boolean,
    val thresholdDb: Float? = null,
    val minSoundMs: Long? = null,
    val headScanMs: Long? = null,
    val tailScanMs: Long? = null,
    val tailConfirmMs: Long? = null,
)

@Serializable
data class TrackInfo(
    val identifier: String,
    val title: String,
    val author: String,
    val duration: Long,
    val uri: String?,
    val isStream: Boolean,
    val isSeekable: Boolean,
    val source: String,
)

@Serializable
data class MixerState(
    /** Echoes the request guild id verbatim (string snowflake). */
    val guildId: String,
    val hasNext: Boolean,
    val overlayActive: Boolean,
    val crossfadeActive: Boolean,
    val crossfadeEnabled: Boolean,
    val crossfadeMs: Long,
    val fadeInMs: Long,
    val duckLevel: Float,
    val normalizationEnabled: Boolean = false,
    val silenceEnabled: Boolean,
    val silenceThresholdDb: Float,
    val tailConfirmMs: Long,
    /** [leadMs, trailMs] of the queued track when already analyzed, else null. */
    val nextTrimMs: List<Long>? = null,
    /** Identifier of the queued next track (pending or ready), so clients can verify server-side ownership. */
    val nextTrackIdentifier: String? = null,
    val nextTrackTitle: String? = null,
    /** Silence-analysis progress of the queued next track: idle | pending | analyzing | ready. */
    val analysisState: String = "idle",
    /** Identifier of the armed overlay/overlap sub track, while the secondary decoder is in use. */
    val subTrackIdentifier: String? = null,
    /** False once the heartbeat probe proved the mixer filter never entered the running track's chain. */
    val mixerFilterActive: Boolean = true,
    /** Milliseconds until an armed overlap hands off; null when no crossfade is running. */
    val crossfadeHandoffInMs: Long? = null,
    /** Announcements (TTS/overlay) waiting for the secondary player, in queue order. */
    val announcementsQueued: Int = 0,
    /** Applied gain of the currently playing main track (1 while unknown/disabled). */
    val currentTrackGain: Float = 1f,
    /** [currentTrackGain] in dB (negative = attenuated). */
    val currentTrackGainDb: Float = 0f,
    /** Analyzed gain of the queued next track; null while analysis is pending. */
    val nextTrackGain: Float? = null,
    /** [nextTrackGain] in dB. */
    val nextTrackGainDb: Float? = null,
)

/**
 * Control plane for the mixer plugin. All endpoints live under the /mixer
 * path prefix alongside the server's own routes.
 *
 * Note enabling audio output also requires the filters op once per guild:
 * `"pluginFilters": { "mixer": { "guildId": "<id>" } }`.
 */
@Service
@RestController
class MixerRestHandler(private val plugin: MixerPlugin) {

    @GetMapping("/mixer/info")
    fun info(): ResponseEntity<PluginInfoResponse> =
        ResponseEntity.ok(PluginInfoResponse(protocolVersion = 2, version = "1.1.0"))

    @PostMapping("/mixer/queue/arm")
    fun arm(
        @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key", required = false) idemKey: String?,
        @RequestBody body: ArmRequest,
    ): ResponseEntity<Any> {
        val key = idemKey ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header")
        val guildId = gid(body.guildId)
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        val track = body.encodedTrack?.let { plugin.decodeTrack(it) }
        return when (val res = mixer.arm(body.epoch, key, track, body.isEmpty)) {
            is ArmResult.Success -> ResponseEntity.ok(mapOf("state" to res.state.name, "epoch" to res.epoch))
            is ArmResult.Duplicate -> ResponseEntity.ok(mapOf("duplicate" to true))
            is ArmResult.Stale -> ResponseEntity.status(HttpStatus.CONFLICT).body(ConflictErrorResponse("STALE_EPOCH", res.lastKnown))
            is ArmResult.PreResetArrival -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "PRE_RESET_ARRIVAL"))
            is ArmResult.PayloadMismatch -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to "Payload mismatch for Idempotency-Key"))
        }
    }

    @PostMapping("/mixer/queue/invalidate")
    fun invalidate(
        @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key", required = false) idemKey: String?,
        @RequestBody body: InvalidateRequest,
    ): ResponseEntity<Any> {
        val key = idemKey ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header")
        val guildId = gid(body.guildId)
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return when (val res = mixer.invalidate(body.epoch, key)) {
            is InvalidateResult.Success -> ResponseEntity.ok(mapOf("state" to res.state.name, "epoch" to res.epoch, "refiredNeedNext" to res.refiredNeedNext))
            is InvalidateResult.Duplicate -> ResponseEntity.ok(mapOf("duplicate" to true))
            is InvalidateResult.Stale -> ResponseEntity.status(HttpStatus.CONFLICT).body(ConflictErrorResponse("STALE_EPOCH", res.lastKnown))
            is InvalidateResult.PreResetArrival -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "PRE_RESET_ARRIVAL"))
        }
    }

    @PostMapping("/mixer/queue/override")
    fun override(
        @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key", required = false) idemKey: String?,
        @RequestBody body: OverrideRequest,
    ): ResponseEntity<Any> {
        val key = idemKey ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header")
        val guildId = gid(body.guildId)
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        val track = body.encodedTrack?.let { plugin.decodeTrack(it) }
        return when (val res = mixer.override(body.epoch, key, body.action, track)) {
            is OverrideResult.Success -> ResponseEntity.ok(mapOf("state" to res.state.name, "epoch" to res.epoch))
            is OverrideResult.Duplicate -> ResponseEntity.ok(mapOf("duplicate" to true))
            is OverrideResult.BadRequest -> ResponseEntity.badRequest().body(mapOf("error" to res.message))
            is OverrideResult.Stale -> ResponseEntity.status(HttpStatus.CONFLICT).body(ConflictErrorResponse("STALE_EPOCH", res.lastKnown))
            is OverrideResult.PreResetArrival -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "PRE_RESET_ARRIVAL"))
            is OverrideResult.PayloadMismatch -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to "Payload mismatch for Idempotency-Key"))
        }
    }

    @PostMapping("/mixer/reset")
    fun reset(
        @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key", required = false) idemKey: String?,
        @RequestBody body: ResetRequest,
    ): ResponseEntity<Any> {
        val key = idemKey ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header")
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        val res = mixer.reset(body.epoch, key) as ResetResult.Success
        return ResponseEntity.ok(mapOf<String, Any>("lastKnownEpoch" to res.lastKnownEpoch))
    }


    @GetMapping("/mixer/state")
    fun state(@RequestParam guildId: Long): ResponseEntity<MixerState> {
        return stateById(guildId.toString(), guildId)
    }

    private fun stateById(raw: String, guildId: Long): ResponseEntity<MixerState> {
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return ResponseEntity.ok(
            MixerState(
                guildId = raw,
                hasNext = mixer.hasNext(),
                overlayActive = mixer.isOverlayActive(),
                crossfadeActive = mixer.isXfadeActive(),
                crossfadeEnabled = mixer.crossfadeEnabled,
                crossfadeMs = mixer.crossfadeMs,
                fadeInMs = mixer.fadeInMs,
                duckLevel = mixer.duckLevel,
                normalizationEnabled = mixer.normalizationEnabled,
                silenceEnabled = mixer.silenceSkipEnabled,
                silenceThresholdDb = mixer.silenceThresholdDb,
                tailConfirmMs = mixer.tailConfirmMs,
                nextTrimMs = mixer.peekNextTrim()?.let { listOf(it.leadMs, it.trailMs) },
                nextTrackIdentifier = mixer.nextTrackIdentifier(),
                nextTrackTitle = mixer.nextTrackTitle(),
                analysisState = mixer.analysisState(),
                subTrackIdentifier = mixer.subTrackIdentifier(),
                mixerFilterActive = mixer.mixerFilterActive(),
                crossfadeHandoffInMs = mixer.crossfadeHandoffInMs(),
                announcementsQueued = mixer.announcementQueueSize(),
                currentTrackGain = mixer.currentTrackGain(),
                currentTrackGainDb = gainToDb(mixer.currentTrackGain()),
                nextTrackGain = mixer.nextTrackGain(),
                nextTrackGainDb = mixer.nextTrackGain()?.let { gainToDb(it) },
            )
        )
    }

    /** Linear gain → dB (e.g. gain 0.5 = -6.0dB). */
    private fun gainToDb(gain: Float): Float = 20f * log10(gain)

    @PostMapping("/mixer/crossfade")
    fun crossfade(@RequestBody body: CrossfadeRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.crossfadeEnabled = body.enabled
        body.durationMs?.let { mixer.crossfadeMs = it.coerceIn(500, 30000) }
        return stateById(body.guildId, guildId)
    }

    @PostMapping("/mixer/fade-in")
    fun fadeIn(@RequestBody body: FadeInRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.fadeInMs = body.durationMs.coerceIn(0, 10000)
        return stateById(body.guildId, guildId)
    }

    @PostMapping("/mixer/announce")
    fun announce(@RequestBody body: AnnounceRequest): ResponseEntity<AnnounceQueuedResponse> {
        requireTrackRef(body.identifier, body.encodedTrack)
        val guildId = gid(body.guildId)
        val mode = when (body.mode?.lowercase()) {
            null, "", "sequential" -> GuildMixer.AnnounceMode.SEQUENTIAL
            "parallel" -> GuildMixer.AnnounceMode.PARALLEL
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown announce mode '${body.mode}' (use 'sequential' or 'parallel')")
        }
        val (id, position) = plugin.announce(guildId, body.identifier, body.encodedTrack, body.duckLevel, mode)
        return ResponseEntity.accepted().body(
            AnnounceQueuedResponse(
                guildId = body.guildId,
                id = id,
                position = position,
                queueLength = plugin.find(guildId)?.announcementQueueSize() ?: position,
            )
        )
    }

    @DeleteMapping("/mixer/announce/queue")
    fun clearAnnounceQueue(@RequestParam guildId: Long): ResponseEntity<Map<String, Int>> {
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return ResponseEntity.ok(mapOf("cleared" to mixer.clearAnnouncements()))
    }

    @PostMapping("/mixer/announce/cancel")
    fun cancelAnnounce(@RequestBody body: AnnounceCancelRequest): ResponseEntity<Unit> {
        val guildId = gid(body.guildId)
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return if (mixer.cancelOverlay()) ResponseEntity.noContent().build()
        else throw ResponseStatusException(HttpStatus.NOT_FOUND, "No active overlay on guild $guildId")
    }

    @PostMapping("/mixer/normalization")
    fun normalization(@RequestBody body: NormalizationRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.normalizationEnabled = body.enabled
        body.targetLufs?.let { mixer.targetLufs = it.coerceIn(-30.0f, -6.0f) }
        return stateById(body.guildId, guildId)
    }

    @PostMapping("/mixer/silence")
    fun silence(@RequestBody body: SilenceRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.silenceSkipEnabled = body.enabled
        body.thresholdDb?.let { mixer.silenceThresholdDb = it.coerceIn(-90f, -20f) }
        body.minSoundMs?.let { mixer.silenceMinSoundMs = it.coerceIn(20, 2000) }
        body.headScanMs?.let { mixer.silenceHeadScanMs = it.coerceIn(1000, 60000) }
        body.tailScanMs?.let { mixer.silenceTailScanMs = it.coerceIn(1000, 60000) }
        body.tailConfirmMs?.let { mixer.tailConfirmMs = it.coerceIn(300, 10000) }
        return stateById(body.guildId, guildId)
    }

    private fun await(future: java.util.concurrent.CompletableFuture<AudioTrack>, identifier: String): AudioTrack {
        // preloadNext already attached queueing; this join only surfaces load errors.
        return try {
            awaitFuture(future, identifier)
        } catch (e: ResponseStatusException) {
            throw e
        }
    }

    private fun awaitFuture(future: java.util.concurrent.CompletableFuture<AudioTrack>, identifier: String): AudioTrack {
        return try {
            future.get(LOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw ResponseStatusException(HttpStatus.ACCEPTED, "Still loading $identifier", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is NoMatchException) throw ResponseStatusException(HttpStatus.BAD_REQUEST, cause.message, cause)
            if (cause is MixerBusyException) throw ResponseStatusException(HttpStatus.CONFLICT, cause.message, cause)
            log.warn("track load failed", cause ?: e)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Load failed: ${cause?.message}", cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted", e)
        }
    }

    private fun AudioTrack.toInfo(): TrackInfo {
        val info = this.info
        return TrackInfo(
            identifier = info.identifier,
            title = info.title,
            author = info.author,
            duration = info.length,
            uri = info.uri,
            isStream = info.isStream,
            isSeekable = this.isSeekable,
            source = try {
                this.sourceManager.javaClass.simpleName
            } catch (e: Exception) {
                "unknown"
            },
        )
    }

    private fun notFound(guildId: Long) =
        ResponseStatusException(HttpStatus.NOT_FOUND, "No mixer state for guild $guildId (player never seen?)")

    private fun gid(raw: String): Long =
        raw.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid guildId: $raw")

    private fun requireTrackRef(identifier: String?, encodedTrack: String?) {
        if (identifier.isNullOrBlank() && encodedTrack.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide identifier or encodedTrack")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixerRestHandler::class.java)
        private const val LOAD_TIMEOUT_SEC = 20L
    }
}
