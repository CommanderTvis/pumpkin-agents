package io.github.commandertvis.pumpkins.maps

import io.github.commandertvis.pumpkins.world.Cell
import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos
import io.github.commandertvis.pumpkins.world.Terrain
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

object MapIO {
    @Suppress("UNCHECKED_CAST")
    fun load(input: InputStream): NamedMap {
        val data = Yaml().load<Map<String, Any?>>(input)
        val name = data["name"] as? String ?: "unnamed"
        val width = (data["width"] as Number).toInt()
        val height = (data["height"] as Number).toInt()
        val legend = (data["legend"] as Map<String, String>).mapValues { decodeSymbol(it.value) }
        val brains = (data["brains"] as? List<*>)?.map { it.toString().uppercase() }
        val rows: List<String> = when (val raw = data["rows"]) {
            is List<*> -> raw.map { it.toString() }
            is String -> raw.split('\n').filter { it.isNotBlank() }.map { it.trimEnd('\r') }
            else -> error("rows must be a list or a block scalar")
        }
        require(rows.size == height) { "expected $height rows, got ${rows.size}" }
        val cells = HashMap<Pos, Cell>()
        for (z in 0 until height) {
            val row = rows[z]
            require(row.length == width) { "row $z: width ${row.length} != $width" }
            for (x in 0 until width) {
                val sym = row[x].toString()
                val meaning = legend[sym] ?: Symbol(Terrain.FLOOR, emptySet())
                cells[Pos(x, z)] = Cell(meaning.terrain, meaning.tags)
            }
        }
        return NamedMap(name, GridWorld(width, height, cells), brains)
    }

    fun load(path: Path): NamedMap = path.inputStream().use { load(it) }

    fun loadResource(resourcePath: String): NamedMap {
        val cl = MapIO::class.java.classLoader
        val stream = cl.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("resource not found: $resourcePath")
        return stream.use { load(it) }
    }

    fun save(world: GridWorld, name: String, path: Path) {
        val text = buildString {
            appendLine("name: $name")
            appendLine("width: ${world.width}")
            appendLine("height: ${world.height}")
            appendLine("legend:")
            appendLine("  \"#\": WALL")
            appendLine("  \".\": FLOOR")
            appendLine("  \"R\": GOAL_RED")
            appendLine("  \"B\": GOAL_BLUE")
            appendLine("  \"G\": GOLD")
            appendLine("  \"P\": PIT")
            appendLine("  \"W\": WUMPUS")
            appendLine("rows: |")
            for (z in 0 until world.height) {
                append("  ")
                for (x in 0 until world.width) {
                    val c = world.cell(Pos(x, z))
                    append(
                        when {
                            c.terrain == Terrain.WALL -> '#'
                            CellTag.GOAL_RED in c.tags -> 'R'
                            CellTag.GOAL_BLUE in c.tags -> 'B'
                            CellTag.GOLD in c.tags -> 'G'
                            CellTag.PIT in c.tags -> 'P'
                            CellTag.WUMPUS in c.tags -> 'W'
                            else -> '.'
                        }
                    )
                }
                appendLine()
            }
        }
        path.writeText(text)
    }

    data class Symbol(val terrain: Terrain, val tags: Set<CellTag>)
    /** Loaded map. `brains` is the optional whitelist of brain names this map was designed for. */
    data class NamedMap(val name: String, val world: GridWorld, val brains: List<String>? = null)

    private fun decodeSymbol(spec: String): Symbol = when (spec.uppercase()) {
        "WALL" -> Symbol(Terrain.WALL, emptySet())
        "FLOOR" -> Symbol(Terrain.FLOOR, emptySet())
        "SAND" -> Symbol(Terrain.SAND, emptySet())
        "SNOW" -> Symbol(Terrain.SNOW, emptySet())
        "GOAL_RED" -> Symbol(Terrain.FLOOR, setOf(CellTag.GOAL_RED))
        "GOAL_BLUE" -> Symbol(Terrain.FLOOR, setOf(CellTag.GOAL_BLUE))
        "GOAL_GREEN" -> Symbol(Terrain.FLOOR, setOf(CellTag.GOAL_GREEN))
        "GOAL_YELLOW" -> Symbol(Terrain.FLOOR, setOf(CellTag.GOAL_YELLOW))
        "GOLD" -> Symbol(Terrain.FLOOR, setOf(CellTag.GOLD))
        "IRON" -> Symbol(Terrain.FLOOR, setOf(CellTag.IRON))
        "PIT" -> Symbol(Terrain.FLOOR, setOf(CellTag.PIT))
        "WUMPUS" -> Symbol(Terrain.FLOOR, setOf(CellTag.WUMPUS))
        else -> error("unknown legend value: $spec")
    }
}
