package io.github.commandertvis.pumpkins.events

import io.github.commandertvis.plugin.handleCancellable
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin

/** The whole world is plugin-painted; players are spectators, so they can't dig up the map. */
fun registerBlockProtectionHandler(plugin: Plugin) {
    plugin.handleCancellable<BlockBreakEvent> { isCancelled = true }
}
