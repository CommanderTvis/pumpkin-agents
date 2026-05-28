package io.github.commandertvis.pumpkins.agent

import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.Direction
import io.github.commandertvis.pumpkins.world.Pos

data class AgentState(
    val id: Int,
    val pos: Pos,
    val facing: Direction = Direction.SOUTH,
    val carrying: CellTag? = null,
    val brain: String = "REFLEX"
)
