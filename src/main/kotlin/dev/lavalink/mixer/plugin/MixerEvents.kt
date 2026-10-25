package dev.lavalink.mixer.plugin

import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Tracks guild players so each gets a [GuildMixer] with track listeners attached. */
@Service
class MixerEvents(private val plugin: MixerPlugin) : PluginEventHandler() {
    override fun onNewPlayer(context: ISocketContext, player: IPlayer) {
        val mixer = plugin.getOrCreate(player.guildId)
        log.info(
            "player created for guild {} -> mixer {} (filter build logs the same identity, " +
                "so mismatched hashes mean the filter is bound to a different instance)",
            player.guildId, MixerFilterExtension.describe(mixer),
        )
        mixer.attachMain(player)
    }

    override fun onDestroyPlayer(context: ISocketContext, player: IPlayer) {
        val mixer = plugin.find(player.guildId)
        if (mixer == null) {
            log.info("player destroyed for guild {} (mixer already gone)", player.guildId)
            return
        }
        // Two players for one guild coexist briefly across socket sessions
        // (a resuming session racing a fresh restore). Tearing the mixer down
        // here would kill the survivor's crossfade state, and the surviving
        // player's chain would lose its GuildMixer entirely. Only the mixer
        // whose main (attached) player is being destroyed goes with it.
        if (!mixer.isMain(player)) {
            log.info(
                "player destroyed for guild {} but mixer {} is attached to a different player; mixer kept",
                player.guildId, MixerFilterExtension.describe(mixer),
            )
            return
        }
        log.info(
            "player destroyed for guild {} (mixer {})",
            player.guildId, MixerFilterExtension.describe(mixer),
        )
        plugin.remove(player.guildId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixerEvents::class.java)
    }
}
