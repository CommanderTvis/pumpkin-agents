package io.github.commandertvis.pumpkins.test

import io.github.commandertvis.pumpkins.maps.MapIO
import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos
import kotlin.io.path.Path
import kotlin.io.path.exists

object Maps {
    private val repoMaps = Path("..", "maps")

    fun load(name: String): GridWorld {
        val f = repoMaps.resolve("$name.yml")
        require(f.exists()) { "missing test map: $f" }
        return MapIO.load(f).world
    }

    fun goalsOf(world: GridWorld): Set<Pos> =
        listOf(
            CellTag.GOAL_RED, CellTag.GOAL_BLUE,
            CellTag.GOAL_GREEN, CellTag.GOAL_YELLOW
        ).flatMap { world.findTag(it) }.toSet()
}
