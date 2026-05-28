package io.github.commandertvis.pumpkins.world

data class Pos(val x: Int, val z: Int) {
    fun shift(dir: Direction): Pos = Pos(x + dir.dx, z + dir.dz)
    fun manhattan(other: Pos): Int = kotlin.math.abs(x - other.x) + kotlin.math.abs(z - other.z)
}

enum class Direction(val dx: Int, val dz: Int) {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    fun turnRight(): Direction = entries[(ordinal + 1) % 4]
    fun turnLeft(): Direction = entries[(ordinal + 3) % 4]
    fun opposite(): Direction = entries[(ordinal + 2) % 4]
}

enum class Terrain(val cost: Int) {
    FLOOR(1),
    SAND(1),
    SNOW(3),
    WALL(Int.MAX_VALUE);
}

enum class CellTag {
    GOAL_RED, GOAL_BLUE, GOAL_GREEN, GOAL_YELLOW,
    GOLD, IRON,
    PIT, WUMPUS;

    fun isGoal(): Boolean = this in GOAL_TAGS

    companion object {
        val GOAL_TAGS: Set<CellTag> = setOf(GOAL_RED, GOAL_BLUE, GOAL_GREEN, GOAL_YELLOW)
    }
}

data class Cell(
    val terrain: Terrain = Terrain.FLOOR,
    val tags: Set<CellTag> = emptySet(),
) {
    val passable: Boolean get() = terrain != Terrain.WALL
}

data class GridWorld(
    val width: Int,
    val height: Int,
    val cells: Map<Pos, Cell>,
) {
    fun cell(p: Pos): Cell = cells[p] ?: Cell()
    fun inBounds(p: Pos): Boolean = p.x in 0 until width && p.z in 0 until height
    fun passable(p: Pos): Boolean = inBounds(p) && cell(p).passable

    fun neighbours(p: Pos): List<Pair<Direction, Pos>> =
        Direction.entries.map { it to p.shift(it) }.filter { passable(it.second) }

    fun findTag(tag: CellTag): List<Pos> = cells.entries.filter { tag in it.value.tags }.map { it.key }

    companion object {
        fun empty(w: Int, h: Int): GridWorld = GridWorld(w, h, emptyMap())
    }
}
