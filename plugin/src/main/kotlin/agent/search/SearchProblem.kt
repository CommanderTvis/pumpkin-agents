package io.github.commandertvis.pumpkins.agent.search

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.world.Direction
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos

/** Russell & Norvig §3.1 — the five components of a search problem. */
interface SearchProblem<S> {
    val initial: S
    fun isGoal(state: S): Boolean
    fun actions(state: S): List<Action>
    fun result(state: S, action: Action): S
    fun stepCost(state: S, action: Action, next: S): Int
}

/** Grid-search problem for a single agent: state = position. */
class GridPathProblem(
    private val world: GridWorld,
    override val initial: Pos,
    private val goals: Set<Pos>,
    private val blocked: Set<Pos> = emptySet(),
) : SearchProblem<Pos> {
    override fun isGoal(state: Pos): Boolean = state in goals

    override fun actions(state: Pos): List<Action> = Direction.entries.mapNotNull { dir ->
        val next = state.shift(dir)
        if (world.passable(next) && next !in blocked) Action.Move(dir) else null
    }

    override fun result(state: Pos, action: Action): Pos = when (action) {
        is Action.Move -> state.shift(action.dir)
        else -> state
    }

    override fun stepCost(state: Pos, action: Action, next: Pos): Int =
        world.cell(next).terrain.cost
}
