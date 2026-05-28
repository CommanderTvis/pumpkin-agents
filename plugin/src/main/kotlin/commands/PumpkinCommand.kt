package io.github.commandertvis.pumpkins.commands

import io.github.commandertvis.plugin.command
import io.github.commandertvis.plugin.command.arguments.IntArg
import io.github.commandertvis.plugin.command.arguments.StringArg
import io.github.commandertvis.plugin.replyComponents
import io.github.commandertvis.pumpkins.agent.Introspectable
import io.github.commandertvis.pumpkins.runtime.AgentRuntime
import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.Pos
import io.github.commandertvis.pumpkins.world.WorldPainter
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

private val BRAINS = listOf(
    "REFLEX", "BFS", "DFS", "UCS",
    "ASTAR", "ASTAR_EUCLID", "ASTAR_BAD",
    "MINIMAX", "ALPHABETA", "PROLOG"
)

private val RUN_TICK_HINTS = listOf("20", "50", "100", "200")

/**
 * Register the `/pumpkin` command via the plugin-api 19.0.0 DSL.
 *
 * Each subcommand declares typed `argument(...)` properties whose values are
 * bound by the runtime and read inside `execute { … }` via property delegation
 * (`val x by argument(IntArg)`). Tab completion comes from each argument's
 * `completer { … }` (overrides the type's default). Parse errors are routed
 * through [io.github.commandertvis.plugin.command.dsl.CommandScope.errorHandler].
 */
fun registerPumpkinCommand(plugin: Plugin, runtime: AgentRuntime) {
    plugin.command("pumpkin") {
        description = "Pumpkin agents control"

        exceptionHandler { sender, _, _, throwable ->
            when (throwable) {
                is IllegalArgumentException ->
                    sender.sendMessage(ChatColor.RED.toString() + (throwable.message ?: "invalid argument"))

                else -> {
                    plugin.logger.severe("Unexpected /pumpkin exception: ${throwable.javaClass.name}: ${throwable.message}")
                    sender.sendMessage(ChatColor.RED.toString() + "internal error; ask the server admin to check the logs")
                }
            }
        }

        execute {
            sender.sendMessage(ChatColor.YELLOW.toString() + "/pumpkin <spawn|map|run|step|reset|metrics|state>")
        }

        subcommand("spawn") {
            description = "Spawn an agent with the given brain at (x, z)"
            val brain by argument(StringArg) {
                description = "brain id"
                completer { _, _, partial, _ -> filterByPrefix(runtime.selectableBrains(BRAINS), partial) }
            }
            val x by argument(IntArg) {
                description = "x coordinate"
                completer { sender, _, partial, _ -> playerCoord(sender, axisX = true, partial = partial) }
            }
            val z by argument(IntArg) {
                description = "z coordinate"
                completer { sender, _, partial, _ -> playerCoord(sender, axisX = false, partial = partial) }
            }
            execute {
                runtime.spawn(brain, Pos(x, z))
                sender.success("spawned $brain at ($x, $z)")
            }
        }

        subcommand("map") {
            description = "Load or save the active map"

            subcommand("load") {
                val name by argument(StringArg) {
                    completer { _, _, partial, _ -> filterByPrefix(availableMaps(runtime), partial) }
                }
                execute {
                    runtime.loadMap(name)
                    val goalCount = (
                        runtime.world.findTag(CellTag.GOAL_RED) +
                            runtime.world.findTag(CellTag.GOAL_BLUE) +
                            runtime.world.findTag(CellTag.GOAL_GREEN) +
                            runtime.world.findTag(CellTag.GOAL_YELLOW) +
                            runtime.world.findTag(CellTag.GOLD)
                        ).size
                    sender.success("loaded $name (${runtime.world.width}x${runtime.world.height}, $goalCount goal/gold)")
                    teleportToMap(sender, runtime)
                }
            }

            subcommand("save") {
                val name by argument(StringArg)
                execute {
                    runtime.saveMap(name)
                    sender.success("saved $name.yml")
                }
            }
        }

        subcommand("run") {
            description = "Run the scheduler for N ticks"
            val ticks by argument(IntArg) {
                description = "tick count"
                completer { _, _, partial, _ -> filterByPrefix(RUN_TICK_HINTS, partial) }
            }
            execute {
                if (!runtime.runFor(ticks)) sender.error("no agents to run; spawn one first")
                else sender.success("running for $ticks ticks")
            }
        }

        subcommand("step") {
            description = "Advance the scheduler by a single tick"
            execute {
                if (runtime.step()) sender.success("stepped to tick ${runtime.currentScheduler()?.ticks ?: 0}")
                else sender.error("no scheduler; spawn an agent first")
            }
        }

        subcommand("reset") {
            description = "Reset the runtime"
            execute {
                runtime.reset()
                sender.success("reset")
            }
        }

        subcommand("metrics") {
            description = "Show the metrics summary"
            execute {
                runtime.recordMetrics("METRICS_REQUEST")
                val summary = runtime.metrics.summary()
                sender.replyComponents {
                    color(ChatColor.GRAY)
                    text { +summary }
                }
            }
        }

        subcommand("state") {
            description = "Show the current state snapshot"
            execute {
                val snap = runtime.snapshot()
                if (snap.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW.toString() + "no agents")
                } else {
                    sender.sendMessage(
                        ChatColor.GRAY.toString() +
                            "map=${runtime.currentMapName} ticks=${runtime.currentScheduler()?.ticks ?: 0}"
                    )
                    val brains = runtime.currentScheduler()?.agents ?: emptyMap()
                    snap.forEach {
                        sender.sendMessage(
                            ChatColor.GRAY.toString() +
                                " id=${it.id} brain=${it.brain} pos=(${it.pos.x},${it.pos.z}) " +
                                "facing=${it.facing} carrying=${it.carrying ?: '-'}"
                        )
                        val brain = brains[it.id]?.second
                        if (brain is Introspectable) {
                            for (line in brain.debugLines()) {
                                sender.sendMessage(ChatColor.DARK_GRAY.toString() + "   $line")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun filterByPrefix(candidates: List<String>, partial: String): List<String> =
    candidates.filter { it.startsWith(partial, ignoreCase = true) }

private fun availableMaps(runtime: AgentRuntime): List<String> =
    runCatching {
        runtime.mapsDir.listDirectoryEntries("*.yml")
            .map { it.nameWithoutExtension }
            .sorted()
    }.getOrDefault(emptyList())

private fun playerCoord(sender: CommandSender, axisX: Boolean, partial: String): List<String> {
    val player = sender as? Player ?: return emptyList()
    val coord = if (axisX) player.location.blockX else player.location.blockZ
    val candidate = coord.toString()
    return if (candidate.startsWith(partial)) listOf(candidate) else emptyList()
}

/**
 * On `/pumpkin map load`, hover the player above the centre of the just-painted grid so
 * they can see the map. The painted grid lives at world coords `(0..width-1, 63..65, 0..height-1)`.
 */
private fun teleportToMap(sender: CommandSender, runtime: AgentRuntime) {
    val player = sender as? Player ?: return
    val w = runtime.world.width
    val h = runtime.world.height
    val loc = player.location
    val withinXZ = loc.blockX in 0 until w && loc.blockZ in 0 until h
    val aboveFloor = loc.y > WorldPainter.Y_OBSTACLE
    if (withinXZ && aboveFloor) return
    val y = (WorldPainter.Y_OBSTACLE + OVERVIEW_HEIGHT).toDouble()
    val target = Location(player.world, (w / 2).toDouble() + 0.5, y, (h / 2).toDouble() + 0.5, 0f, 90f)
    player.teleport(target)
}

private const val OVERVIEW_HEIGHT = 12

private fun CommandSender.success(msg: String) = sendMessage(ChatColor.GREEN.toString() + msg)
private fun CommandSender.error(msg: String) = sendMessage(ChatColor.RED.toString() + msg)
