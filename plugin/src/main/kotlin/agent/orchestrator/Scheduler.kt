package io.github.commandertvis.pumpkins.agent.orchestrator

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.AgentState
import io.github.commandertvis.pumpkins.agent.Brain
import io.github.commandertvis.pumpkins.agent.Percept
import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.GridWorld

/**
 * Atomic-tick scheduler. Collects every agent's chosen action, then applies them
 * deterministically: lower id wins on conflict; the loser gets [Action.Wait].
 */
class Scheduler(
    val world: GridWorld,
    initialAgents: List<Pair<AgentState, Brain>>
) {
    private val state = LinkedHashMap<Int, Pair<AgentState, Brain>>()
    val agents: Map<Int, Pair<AgentState, Brain>> get() = state
    var ticks: Long = 0
        private set

    init {
        initialAgents.sortedBy { it.first.id }.forEach { state[it.first.id] = it }
    }

    fun tick(localOnly: Set<Int> = emptySet()): Map<Int, Action> {
        val proposals = LinkedHashMap<Int, Action>()
        val agentStates = state.values.map { it.first }
        for ((id, pair) in state) {
            val (s, brain) = pair
            val percept: Percept = if (id in localOnly) {
                synthesiseLocal(s)
            } else {
                Percept.FullView(self = s, world = world, others = agentStates.filter { it.id != id })
            }
            proposals[id] = brain.decide(percept)
        }
        val resolved = ConflictResolver.resolve(state.mapValues { it.value.first }, proposals)
        for ((id, action) in resolved) {
            val (s, b) = state.getValue(id)
            state[id] = apply(s, action) to b
        }
        ticks++
        return resolved
    }

    fun snapshot(): List<AgentState> = state.values.map { it.first }

    fun update(id: Int, mutator: (AgentState) -> AgentState) {
        state[id]?.let { state[id] = mutator(it.first) to it.second }
    }

    fun anyAtGoal(goalTags: Set<CellTag>): Boolean = state.values.any { (s, _) ->
        world.cell(s.pos).tags.any { it in goalTags }
    }

    private fun apply(s: AgentState, action: Action): AgentState = when (action) {
        is Action.Move -> {
            val np = s.pos.shift(action.dir)
            val moved = if (world.passable(np)) s.copy(pos = np, facing = action.dir) else s.copy(facing = action.dir)
            // Auto-pickup: stepping onto a resource cell while empty-handed lifts it.
            autoPickup(moved)
        }
        Action.Pickup -> autoPickup(s)
        Action.Drop -> if (s.carrying != null) s.copy(carrying = null) else s
        Action.Wait -> s
    }

    private fun autoPickup(s: AgentState): AgentState {
        if (s.carrying != null) return s
        val tag = world.cell(s.pos).tags.firstOrNull { it == CellTag.GOLD || it == CellTag.IRON } ?: return s
        return s.copy(carrying = tag)
    }

    private fun synthesiseLocal(s: AgentState): Percept.Local {
        val adj = io.github.commandertvis.pumpkins.world.Direction.entries.map { s.pos.shift(it) }
        val pit = adj.any { CellTag.PIT in world.cell(it).tags }
        val wump = adj.any { CellTag.WUMPUS in world.cell(it).tags }
        val gold = CellTag.GOLD in world.cell(s.pos).tags
        return Percept.Local(s, world, breeze = pit, stench = wump, glitter = gold, bump = false)
    }
}
