package io.github.commandertvis.pumpkins.events

import io.github.commandertvis.plugin.handle
import org.bukkit.ChatColor
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin

fun registerJoinHandler(plugin: Plugin) {
    plugin.handle<PlayerJoinEvent> {
        val g = ChatColor.GREEN.toString()
        val y = ChatColor.YELLOW.toString()
        val w = ChatColor.WHITE.toString()
        val gray = ChatColor.GRAY.toString()
        val rst = ChatColor.RESET.toString()
        player.sendMessage(
            "$g${ChatColor.BOLD}Welcome to PumpkinAgents.$rst",
            "$gray You're standing on a flat world. " +
                    "The plugin animates ${y}CARVED_PUMPKIN$gray blocks as autonomous AI agents.$rst",
            "$gray Try the following:$rst",
            "  $w/pumpkin map load corridor$gray   $rst- load a benchmark map",
            "  $w/pumpkin spawn BFS 1 1$gray       $rst- create a BFS agent at (1, 1)",
            "  $w/pumpkin run 50$gray              $rst- run 50 decision ticks asynchronously",
            "  $w/pumpkin step$gray                $rst- step once, synchronously",
            "  $w/pumpkin state$gray               $rst- show all agents and their positions",
            "  $w/pumpkin metrics$gray             $rst- print the per-run metrics table",
            "$gray Brains available: " +
                    "${y}REFLEX BFS DFS UCS ASTAR ASTAR_EUCLID ASTAR_BAD MINIMAX ALPHABETA PROLOG$rst"
        )
    }
}
