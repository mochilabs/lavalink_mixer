package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Coarse, non-atomic bounded cache shared across every guild so repeated tracks
 * only decode once without unbounded growth over long uptime.
 */
internal class BoundedCache<V : Any>(
    private val maxSize: Int = 1000,
) {
    private val map = ConcurrentHashMap<String, V>()

    fun contains(id: String): Boolean = map.containsKey(id)

    operator fun get(id: String): V? = map[id]

    operator fun set(id: String, value: V) {
        map[id] = value
        if (map.size > maxSize) {
            map.keys.firstOrNull()?.let { map.remove(it) }
        }
    }
}

internal object AnalysisCaches {
    val norm = BoundedCache<Float>()
    val trim = BoundedCache<TrimPoints>()
    val bpm = BoundedCache<BpmDetector.BpmResult>()
    val bpmHead = BoundedCache<ExtendedBeatGrid>()
    val bpmTail = BoundedCache<ExtendedBeatGrid>()
    val key = BoundedCache<KeyAnalysis>()
    val featuresHead = BoundedCache<TransitionFeatures>()
    val featuresTail = BoundedCache<TransitionFeatures>()

    fun trimFor(track: AudioTrack): TrimPoints {
        val id = idOf(track) ?: return TrimPoints.NONE
        return trim[id] ?: TrimPoints.NONE
    }

    fun normGainFor(track: AudioTrack, targetLufs: Float, normGainBaseline: Float): Float {
        val id = idOf(track) ?: return 1f
        val measuredLufs = norm[id] ?: return 1f
        val target = if (normGainBaseline != 0f) normGainBaseline else targetLufs
        return NormalizationGain.fromLufs(measuredLufs, targetLufs = target).gain
    }
}
