package io.github.commandertvis.pumpkins

import io.github.commandertvis.plugin.json.JsonConfigurablePlugin
import io.github.commandertvis.pumpkins.commands.registerPumpkinCommand
import io.github.commandertvis.pumpkins.events.registerBlockProtectionHandler
import io.github.commandertvis.pumpkins.events.registerJoinHandler
import io.github.commandertvis.pumpkins.runtime.AgentRuntime
import io.github.commandertvis.pumpkins.runtime.PumpkinConfig
import org.bukkit.GameRule
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.outputStream

@Suppress("unused")
class PumpkinPlugin : JsonConfigurablePlugin<PumpkinConfig>(PumpkinConfig()) {
    lateinit var runtime: AgentRuntime
        private set

    override fun onEnable() {
        saveDefaultConfig()
        val cfg = jsonConfig
        val dataPath: Path = dataFolder.toPath()
        val mapsDir = (dataPath / "maps").also { it.createDirectories() }
        val metricsDir = (dataPath / "metrics").also { it.createDirectories() }
        copyBundledMaps(mapsDir)

        runtime = AgentRuntime(
            plugin = this,
            mapsDir = mapsDir,
            metricsDir = metricsDir,
            decisionsPerSecond = cfg.decisionsPerSecond,
            seed = cfg.randomSeed
        )

        registerPumpkinCommand(this, runtime)
        registerJoinHandler(this)
        registerBlockProtectionHandler(this)
        freezeWorlds()
        logger.info("PumpkinAgents enabled (decisionsPerSecond=${cfg.decisionsPerSecond}, seed=${cfg.randomSeed})")
    }

    /**
     * Pin every loaded world to a stable demo clock: noon, no day/night cycle, no weather.
     * The painted grids read best under steady overhead light — a sun-cycle wash makes the
     * coloured-wool goal cells hard to tell apart on screen recordings.
     */
    private fun freezeWorlds() {
        for (world in server.worlds) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            world.time = NOON_TICKS
            world.setStorm(false)
            world.isThundering = false
        }
    }

    override fun onDisable() {
        if (::runtime.isInitialized) runtime.stop()
    }

    private fun copyBundledMaps(target: Path) {
        val bundled = listOf(
            "corridor.yml",
            "maze_s.yml",
            "multigoal.yml",
            "arena.yml",
            "wumpus_4.yml",
            "wumpus_8.yml",
        )

        for (name in bundled) {
            val out = target.resolve(name)
            if (out.exists()) continue

            javaClass.classLoader.getResourceAsStream("maps/$name")?.use { ins ->
                out.outputStream().use { ins.copyTo(it) }
            }
        }
    }

    private companion object {
        /** Minecraft time-of-day at solar noon (overworld ticks). */
        private const val NOON_TICKS: Long = 6000L
    }
}
