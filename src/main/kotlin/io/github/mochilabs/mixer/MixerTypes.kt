package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack

data class Gains(val main: Float, val sub: Float)

enum class PlaybackState { IDLE, PLAYING, ARMED, NO_NEXT }

/**
 * One concurrent `mode=parallel` TTS overlay. Each slot owns its own
 * secondary [AudioPlayer] and [SiphonQueue]; its decoded PCM is summed into
 * the main chain by [MixerFilter] alongside the (single) crossfade sub.
 * The [ramp] fades the slot in/out on start/finish so a parallel overlay
 * never clicks; the music duck level is handled by [GuildMixer]'s shared
 * mainGain, not per-slot.
 */
class OverlaySlot(
    val id: Long,
    val player: AudioPlayer,
    val siphon: SiphonQueue,
    val channel: AnnounceChannel = AnnounceChannel.BOTH,
) {
    @Volatile var track: AudioTrack? = null
    @Volatile var active: Boolean = false
    val ramp: GainRamp = GainRamp(0f)
}

class MixerBusyException(message: String) : RuntimeException(message)
