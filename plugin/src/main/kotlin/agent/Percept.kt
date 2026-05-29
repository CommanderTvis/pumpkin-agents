package io.github.commandertvis.pumpkins.agent

import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos

sealed interface Percept {
    val self: AgentState
    val world: GridWorld

    /** Full observability: agent sees the entire grid. Phases 0–2. */
    data class FullView(
        override val self: AgentState,
        override val world: GridWorld,
        val others: List<AgentState> = emptyList(),
        val goalTags: Set<CellTag> = setOf(
            CellTag.GOAL_RED,
            CellTag.GOAL_BLUE,
            CellTag.GOAL_GREEN,
            CellTag.GOAL_YELLOW
        ),
    ) : Percept {
        fun goalPositions(): List<Pos> = goalTags.flatMap { world.findTag(it) }
    }

    /** Local 4-neighbourhood percepts for the Wumpus phase. */
    data class Local(
        override val self: AgentState,
        override val world: GridWorld,
        val breeze: Boolean,
        val stench: Boolean,
        val glitter: Boolean,
        val bump: Boolean,
    ) : Percept
}
