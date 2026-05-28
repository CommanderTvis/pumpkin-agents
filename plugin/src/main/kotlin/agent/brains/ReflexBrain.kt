package io.github.commandertvis.pumpkins.agent.brains

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.Brain
import io.github.commandertvis.pumpkins.agent.Percept
import io.github.commandertvis.pumpkins.world.Direction

/**
 * Simple-reflex right-hand wall follower. Maintains its facing in [AgentState.facing].
 * If wall on right -> turn right; else if no wall ahead -> step forward; else turn left.
 */
class ReflexBrain : Brain {
    override fun decide(percept: Percept): Action {
        val s = percept.self
        val world = percept.world
        val rightDir = s.facing.turnRight()
        val rightPassable = world.passable(s.pos.shift(rightDir))
        if (rightPassable) return Action.Move(rightDir)
        val aheadPassable = world.passable(s.pos.shift(s.facing))
        if (aheadPassable) return Action.Move(s.facing)
        val leftDir = s.facing.turnLeft()
        if (world.passable(s.pos.shift(leftDir))) return Action.Move(leftDir)
        return Action.Move(s.facing.opposite())
    }
}
