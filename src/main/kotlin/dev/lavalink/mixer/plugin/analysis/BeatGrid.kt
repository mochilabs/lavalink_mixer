package dev.lavalink.mixer.plugin

/**
 * Beat grid for a decoded region: a regular pulse the mixer can snap a
 * crossfade to. Distinct from a naive tempo float — carries the phase so the
 * mixer knows WHERE beats land, and the confidence so the gate can tell a real
 * pulse apart from an autocorrelation that merely picked *some* lag.
 */
data class BeatGrid(
    val bpm: Float,
    /** Offset (ms) within the analyzed window where a beat first lands, i.e. the
     *  pulse's grid phase — NOT a musical downbeat (no bar/section awareness). */
    val phaseMs: Long,
    /** 0..1 how periodic the onset signal is at the beat lag. */
    val confidence: Float,
    /** Which analysis window produced this grid (head vs tail of track), so caches/keys
     *  can keep head and tail grids distinct. null = head. */
    val windowHeadMs: Long? = null,
    val windowTailMs: Boolean = false,
)