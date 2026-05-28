package io.github.commandertvis.pumpkins.agent.orchestrator

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.AgentState
import io.github.commandertvis.pumpkins.world.Pos

object ConflictResolver {
    /** Deterministic: lower agent id wins on shared-cell conflicts; the loser yields. */
    fun resolve(agents: Map<Int, AgentState>, proposals: Map<Int, Action>): Map<Int, Action> {
        val sortedIds = agents.keys.sorted()
        val claimedNext = HashMap<Pos, Int>()
        val result = LinkedHashMap<Int, Action>()
        for (id in sortedIds) {
            val s = agents.getValue(id)
            val a = proposals[id] ?: Action.Wait
            val target = when (a) {
                is Action.Move -> s.pos.shift(a.dir)
                else -> s.pos
            }
            val occupant = claimedNext[target]
            if (occupant != null && occupant != id) {
                result[id] = Action.Wait
            } else {
                claimedNext[target] = id
                result[id] = a
            }
        }
        return result
    }
}
