package io.github.commandertvis.pumpkins.agent

import io.github.commandertvis.pumpkins.world.Direction

sealed interface Action {
    data class Move(val dir: Direction) : Action
    data object Pickup : Action
    data object Drop : Action
    data object Wait : Action
}
