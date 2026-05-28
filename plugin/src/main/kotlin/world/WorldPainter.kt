package io.github.commandertvis.pumpkins.world

import org.bukkit.Material
import org.bukkit.World

/**
 * Paints a [GridWorld] into a real Bukkit world. On a Paper 1.18+ flat world the
 * default surface sits at y=-61, so we use that layer as the floor and stack
 * obstacles / agents at y=-60 and any carried block at y=-59.
 *
 * Per-agent text labels live in [AgentHud] (HolographicDisplays) — there's no
 * birch-sign slot in the block stack any more.
 */
class WorldPainter(private val world: World) {

    private var painted: Region? = null

    fun paint(grid: GridWorld) {
        painted?.let { clear(it) }
        for (x in 0 until grid.width) for (z in 0 until grid.height) {
            val cell = grid.cell(Pos(x, z))
            world.getBlockAt(x, Y_CARRIED, z).type = Material.AIR
            world.getBlockAt(x, Y_FLOOR, z).type = floorMaterial(cell)
            world.getBlockAt(x, Y_OBSTACLE, z).type = obstacleMaterial(cell)
        }
        painted = Region(grid.width, grid.height)
    }

    fun placeAgent(p: Pos, level: Int = 0) {
        world.getBlockAt(p.x, agentY(level), p.z).type = Material.CARVED_PUMPKIN
    }

    fun removeAgent(p: Pos, level: Int = 0) {
        world.getBlockAt(p.x, agentY(level), p.z).type = Material.AIR
    }

    fun setCarried(p: Pos, carried: CellTag?, level: Int = 0) {
        val material = when (carried) {
            CellTag.GOLD -> Material.GOLD_BLOCK
            CellTag.IRON -> Material.IRON_BLOCK
            null -> Material.AIR
            else -> Material.AIR
        }
        world.getBlockAt(p.x, carriedY(level), p.z).type = material
    }

    fun agentY(level: Int): Int = Y_OBSTACLE + level * STACK_STRIDE
    fun carriedY(level: Int): Int = Y_CARRIED + level * STACK_STRIDE

    private fun floorMaterial(cell: Cell): Material = when {
        CellTag.GOAL_RED in cell.tags -> Material.RED_WOOL
        CellTag.GOAL_BLUE in cell.tags -> Material.BLUE_WOOL
        CellTag.GOAL_GREEN in cell.tags -> Material.LIME_WOOL
        CellTag.GOAL_YELLOW in cell.tags -> Material.YELLOW_WOOL
        cell.terrain == Terrain.SAND -> Material.SAND
        cell.terrain == Terrain.SNOW -> Material.SNOW_BLOCK
        else -> Material.STONE
    }

    private fun obstacleMaterial(cell: Cell): Material = when {
        cell.terrain == Terrain.WALL -> Material.OBSIDIAN
        CellTag.GOLD in cell.tags -> Material.GOLD_BLOCK
        CellTag.IRON in cell.tags -> Material.IRON_BLOCK
        CellTag.PIT in cell.tags -> Material.MAGMA_BLOCK
        CellTag.WUMPUS in cell.tags -> Material.RED_CONCRETE
        else -> Material.AIR
    }

    private fun clear(r: Region) {
        for (x in 0 until r.width) for (z in 0 until r.height) {
            world.getBlockAt(x, Y_CARRIED, z).type = Material.AIR
            world.getBlockAt(x, Y_OBSTACLE, z).type = Material.AIR
            world.getBlockAt(x, Y_FLOOR, z).type = Material.AIR
        }
    }

    private data class Region(val width: Int, val height: Int)

    companion object {
        /** Paper 1.18+ flat-world default surface. */
        const val Y_FLOOR: Int = -61
        const val Y_OBSTACLE: Int = -60

        /** The carried block sits one above the pumpkin. */
        const val Y_CARRIED: Int = -59

        /** Each additional agent on the same cell stacks this many blocks higher. */
        const val STACK_STRIDE: Int = 2
    }
}
