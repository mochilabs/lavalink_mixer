package dev.lavalink.mixer.plugin

sealed class GateResult {
    object Ok : GateResult()
    object Duplicate : GateResult()
    data class Stale(val lastKnown: Long) : GateResult()
    data class PreResetArrival(val windowOpenAt: Long, val lastKnownEpoch: Long) : GateResult()
    object PayloadMismatch : GateResult()
}

sealed class ArmResult {
    data class Success(val state: PlaybackState, val epoch: Long) : ArmResult()
    object Duplicate : ArmResult()
    data class Stale(val lastKnown: Long) : ArmResult()
    data class PreResetArrival(val windowOpenAt: Long, val lastKnownEpoch: Long) : ArmResult()
    object PayloadMismatch : ArmResult()
    data class BadRequest(val message: String) : ArmResult()
}

sealed class InvalidateResult {
    data class Success(val state: PlaybackState, val epoch: Long, val refiredNeedNext: Boolean) : InvalidateResult()
    object Duplicate : InvalidateResult()
    data class Stale(val lastKnown: Long) : InvalidateResult()
    data class PreResetArrival(val windowOpenAt: Long, val lastKnownEpoch: Long) : InvalidateResult()
    object PayloadMismatch : InvalidateResult()
}

sealed class OverrideResult {
    data class Success(val state: PlaybackState, val epoch: Long) : OverrideResult()
    object Duplicate : OverrideResult()
    data class Stale(val lastKnown: Long) : OverrideResult()
    data class PreResetArrival(val windowOpenAt: Long, val lastKnownEpoch: Long) : OverrideResult()
    object PayloadMismatch : OverrideResult()
    data class BadRequest(val message: String) : OverrideResult()
}

sealed class ResetResult {
    data class Success(val lastKnownEpoch: Long) : ResetResult()
    data class Duplicate(val lastKnownEpoch: Long) : ResetResult()
    object PayloadMismatch : ResetResult()
}
