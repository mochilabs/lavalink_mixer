package io.github.mochilabs.mixer

import org.slf4j.LoggerFactory

/**
 * Pure idempotency and epoch/window gate for the v2 control plane.
 *
 * Callers must hold [GuildMixer]'s lock when mutating via [checkAndRecord] or
 * [recordReset].
 */
internal class EpochGate(
    private val guildId: Long,
) {
    @Volatile var lastKnownEpoch: Long = 0L
        private set

    @Volatile var windowOpenAt: Long = 0L
        private set

    private val recentRequestIds = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 64
    }

    fun checkAndRecord(epoch: Long, idemKey: String, fingerprint: String): GateResult {
        val prev = recentRequestIds[idemKey]
        if (prev != null) {
            return if (prev == fingerprint) GateResult.Duplicate else GateResult.PayloadMismatch
        }
        val now = System.currentTimeMillis()
        if (now < windowOpenAt) return GateResult.PreResetArrival(windowOpenAt, lastKnownEpoch)
        if (epoch < lastKnownEpoch) return GateResult.Stale(lastKnownEpoch)
        recentRequestIds[idemKey] = fingerprint
        lastKnownEpoch = epoch
        return GateResult.Ok
    }

    fun recordReset(epoch: Long, idemKey: String, windowOpenGraceMs: Long): ResetResult {
        val fp = "reset|$epoch"
        val prev = recentRequestIds[idemKey]
        if (prev != null) {
            return if (prev == fp) ResetResult.Duplicate(lastKnownEpoch) else ResetResult.PayloadMismatch
        }
        if (epoch < lastKnownEpoch) {
            log.warn(
                "reset epoch {} < lastKnown {} on guild {}; keeping {}",
                epoch,
                lastKnownEpoch,
                guildId,
                lastKnownEpoch,
            )
        }
        lastKnownEpoch = maxOf(lastKnownEpoch, epoch)
        recentRequestIds.clear()
        recentRequestIds[idemKey] = fp
        windowOpenAt = if (windowOpenGraceMs > 0) System.currentTimeMillis() + windowOpenGraceMs else 0L
        return ResetResult.Success(lastKnownEpoch)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(EpochGate::class.java)
    }
}
