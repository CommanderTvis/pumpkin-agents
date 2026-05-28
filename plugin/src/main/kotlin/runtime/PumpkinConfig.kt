package io.github.commandertvis.pumpkins.runtime

/** Serialised to/from `plugins/PumpkinAgents/config.json` by plugin-api's JsonConfigurablePlugin. */
data class PumpkinConfig(
    val decisionsPerSecond: Int = 2,
    val randomSeed: Long = 1234L,
    val defaultBrain: String = "ASTAR"
)
