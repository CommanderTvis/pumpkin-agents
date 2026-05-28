package io.github.commandertvis.pumpkins.runtime

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.AgentState
import io.github.commandertvis.pumpkins.agent.Brain
import io.github.commandertvis.pumpkins.agent.InstrumentedBrain
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.WorldPainter
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.plugin.Plugin
import kotlin.math.log10

/**
 * Transient per-tick particle layer expressing each agent's thinking:
 *
 * - `SPELL_WITCH` swirl at the agent — count scaled by `nodesExpanded` (log-curved so high-compute
 *   brains visibly "boil" while reflex brains barely puff).
 * - `SOUL_FIRE_FLAME` along the planned path the search brain reconstructed this tick — viewers
 *   see *intent*, not just position.
 * - `VILLAGER_HAPPY` burst when an agent's resolved action lands on a goal cell.
 *
 * The class is a no-op if the server has no loaded world.
 */
class BrainParticles(private val plugin: Plugin) {

    fun emit(
        world: GridWorld,
        snapshot: List<AgentState>,
        agents: Map<Int, Pair<AgentState, Brain>>,
        resolved: Map<Int, Action>,
    ) {
        val bw = plugin.server.worlds.firstOrNull() ?: return
        val byId = snapshot.associateBy { it.id }
        for ((id, pair) in agents) {
            val state = byId[id] ?: continue
            val stats = (pair.second as? InstrumentedBrain)?.stats
            val cx = state.pos.x + 0.5
            val cz = state.pos.z + 0.5
            val agentY = WorldPainter.Y_OBSTACLE + AGENT_FACE_Y

            val nodes = stats?.nodesExpanded ?: 0L
            val witchCount = (WITCH_BASE_COUNT + log10(nodes.toDouble() + 1.0) * WITCH_NODES_SCALE)
                .toInt()
                .coerceIn(WITCH_BASE_COUNT, WITCH_MAX_COUNT)
            bw.spawnParticle(
                Particle.SPELL_WITCH,
                Location(bw, cx, agentY, cz),
                witchCount,
                WITCH_SPREAD, WITCH_SPREAD_Y, WITCH_SPREAD,
                0.0,
            )

            val flameY = WorldPainter.Y_FLOOR + FLAME_Y_OFFSET
            for (p in stats?.lastPlan.orEmpty()) {
                bw.spawnParticle(
                    Particle.SOUL_FIRE_FLAME,
                    Location(bw, p.x + 0.5, flameY, p.z + 0.5),
                    FLAME_COUNT,
                    FLAME_SPREAD, FLAME_SPREAD, FLAME_SPREAD,
                    0.0,
                )
            }

            val action = resolved[id]
            if (action is Action.Move && world.cell(state.pos).tags.any { it.isGoal() }) {
                bw.spawnParticle(
                    Particle.VILLAGER_HAPPY,
                    Location(bw, cx, agentY + GOAL_Y_LIFT, cz),
                    GOAL_COUNT,
                    GOAL_SPREAD, GOAL_SPREAD_Y, GOAL_SPREAD,
                    0.0,
                )
            }
        }
    }

    private companion object {
        const val AGENT_FACE_Y = 0.7

        const val WITCH_BASE_COUNT = 3
        const val WITCH_MAX_COUNT = 40
        const val WITCH_NODES_SCALE = 6.0
        const val WITCH_SPREAD = 0.35
        const val WITCH_SPREAD_Y = 0.6

        const val FLAME_Y_OFFSET = 1.15
        const val FLAME_COUNT = 2
        const val FLAME_SPREAD = 0.12

        const val GOAL_Y_LIFT = 0.3
        const val GOAL_COUNT = 24
        const val GOAL_SPREAD = 0.4
        const val GOAL_SPREAD_Y = 0.6
    }
}
