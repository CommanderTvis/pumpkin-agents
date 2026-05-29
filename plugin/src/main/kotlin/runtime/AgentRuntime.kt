package io.github.commandertvis.pumpkins.runtime

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.AgentState
import io.github.commandertvis.pumpkins.agent.Brain
import io.github.commandertvis.pumpkins.agent.InstrumentedBrain
import io.github.commandertvis.pumpkins.agent.brains.*
import io.github.commandertvis.pumpkins.agent.orchestrator.Scheduler
import io.github.commandertvis.pumpkins.agent.search.Heuristics
import io.github.commandertvis.pumpkins.maps.MapIO
import io.github.commandertvis.pumpkins.metrics.MetricsLogger
import io.github.commandertvis.pumpkins.metrics.MetricsRow
import io.github.commandertvis.pumpkins.world.*
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Path
import kotlin.io.path.exists

class AgentRuntime(
    val plugin: Plugin,
    val mapsDir: Path,
    val metricsDir: Path,
    val decisionsPerSecond: Int,
    val seed: Long,
) {
    var world: GridWorld = GridWorld.empty(8, 8)
        private set
    var currentMapName: String = "(empty)"
        private set

    /** Brain names this map was designed for, or `null` to allow any brain. */
    var allowedBrains: List<String>? = null
        private set
    val metrics: MetricsLogger = MetricsLogger(metricsDir)

    /** First Bukkit world ("world"); the canvas the [WorldPainter] writes into. */
    private val bukkitWorld get() = plugin.server.worlds.firstOrNull()

    // Lazy so the painter's `painted` region survives across map loads and the prior
    // footprint is cleared on the next `paint()`.
    private val painter: WorldPainter? by lazy { bukkitWorld?.let { WorldPainter(it) } }
    private val hud: AgentHud by lazy { AgentHud(plugin) }
    private val particles: BrainParticles by lazy { BrainParticles(plugin) }
    private val placedAgents = HashMap<Int, Placed>()

    /** Last HUD snapshot pushed per agent; lets [render] skip no-op hologram rewrites. */
    private val lastHud = HashMap<Int, HudSnapshot>()

    /** Last action each agent picked — drives the "last" line in the hologram. */
    private val lastActions = HashMap<Int, Action>()

    // Per-agent search effort from each agent's first real (node-expanding) decision.
    // A search brain resets its stats every decision, so the live counters read 0 once an
    // agent parks on its goal and only picks Wait; later decisions are also distorted by
    // inter-agent contention (a rival sitting on the nearest goal forces a costlier reroute).
    // The first decision — the search from the start cell, before any of that — is the
    // canonical, reproducible measure, matching the report's "1st decision" column.
    private val firstNodes = HashMap<Int, Long>()
    private val firstFrontier = HashMap<Int, Long>()
    private val firstDecisionMs = HashMap<Int, Long>()

    /** Visual location of an agent: its grid cell plus its stack level when sharing the cell. */
    private data class Slot(val pos: Pos, val level: Int)

    /** What's currently painted for an agent: its [Slot] and the block it carries. */
    private data class Placed(val slot: Slot, val carried: CellTag?)

    private var scheduler: Scheduler? = null
    private var task: BukkitTask? = null
    private val agentsBuilder = mutableListOf<Pair<AgentState, Brain>>()
    private val localPercepts: MutableSet<Int> = HashSet()
    private var nextId: Int = 1
    private var totalTickTimeMs: Long = 0

    fun loadMap(name: String) {
        val file = mapsDir.resolve("$name.yml")
        require(file.exists()) { "map not found: $name" }
        val nm = MapIO.load(file)
        world = nm.world
        currentMapName = nm.name
        allowedBrains = nm.brains
        agentsBuilder.clear()
        nextId = 1
        localPercepts.clear()
        scheduler = null
        stop()
        painter?.paint(world)
        hud.clear()
        hud.showLabels(cellLabels())
        placedAgents.clear()
        lastActions.clear()
        lastHud.clear()
        clearDecisionStats()
    }

    fun saveMap(name: String) {
        val file = mapsDir.resolve("$name.yml")
        MapIO.save(world, name, file)
    }

    /** Brains selectable on the loaded map: the map's whitelist if any, else [fallback]. */
    fun selectableBrains(fallback: List<String>): List<String> = allowedBrains ?: fallback

    fun spawn(brainName: String, pos: Pos) {
        val canonical = brainName.uppercase()
        allowedBrains?.let { allowed ->
            require(canonical in allowed) {
                "brain $canonical is not designed for map $currentMapName (allowed: ${allowed.joinToString()})"
            }
        }
        val brain = newBrain(brainName)
        val agent = AgentState(id = nextId++, pos = pos, facing = Direction.SOUTH, brain = brainName)
        agentsBuilder += agent to brain

        if (brainName.uppercase() == "PROLOG")
            localPercepts += agent.id

        scheduler = Scheduler(world, agentsBuilder.toList())
        render()
    }

    fun step(): Boolean {
        val sched = ensureScheduler() ?: return false
        val started = System.nanoTime()
        val resolved = sched.tick(localPercepts)
        totalTickTimeMs += (System.nanoTime() - started) / 1_000_000
        lastActions.putAll(resolved)
        captureDecisionStats()
        render()
        playSounds(resolved)
        particles.emit(world, sched.snapshot(), sched.agents, resolved)
        return true
    }

    fun runFor(ticks: Int, onTick: (Long) -> Unit = {}): Boolean {
        val sched = ensureScheduler() ?: return false
        if (task != null) task?.cancel()
        val periodTicks = (20L / decisionsPerSecond.coerceAtLeast(1)).coerceAtLeast(1L)
        var remaining = ticks
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (remaining <= 0) {
                task?.cancel(); task = null; recordMetrics("RAN_OUT"); return@Runnable
            }
            val s = System.nanoTime()
            val resolved = sched.tick(localPercepts)
            totalTickTimeMs += (System.nanoTime() - s) / 1_000_000
            lastActions.putAll(resolved)
            captureDecisionStats()
            render()
            playSounds(resolved)
            particles.emit(world, sched.snapshot(), sched.agents, resolved)
            onTick(sched.ticks)
            remaining--
        }, 0L, periodTicks)
        return true
    }

    /** Map per-agent action outcomes to a quiet sound at the agent's new position. */
    private fun playSounds(resolved: Map<Int, Action>) {
        val bw = bukkitWorld ?: return
        val byId = snapshot().associateBy { it.id }
        for ((id, action) in resolved) {
            val state = byId[id] ?: continue
            val loc = Location(bw, state.pos.x + 0.5, (WorldPainter.Y_OBSTACLE + 1).toDouble(), state.pos.z + 0.5)
            val (sound, pitch) = when (action) {
                is Action.Move -> Sound.BLOCK_PUMPKIN_CARVE to 1.6f
                Action.Pickup -> Sound.ENTITY_ITEM_PICKUP to 1.3f
                Action.Drop -> Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM to 0.9f
                Action.Wait -> continue
            }
            bw.playSound(loc, sound, SOUND_VOLUME, pitch)
            // Cheering sound when an agent lands on a goal cell.
            if (action is Action.Move && world.cell(state.pos).tags.any { it.isGoal() }) {
                bw.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, SOUND_VOLUME, 1.0f)
            }
        }
    }

    fun stop() {
        task?.cancel(); task = null
    }

    fun reset() {
        stop()
        // Erase any agent blocks from the world before clearing state.
        painter?.let { p ->
            for (placed in placedAgents.values) {
                p.setCarried(placed.slot.pos, null, placed.slot.level)
                p.removeAgent(placed.slot.pos, placed.slot.level)
            }
        }
        hud.clear()
        agentsBuilder.clear()
        nextId = 1
        localPercepts.clear()
        scheduler = null
        totalTickTimeMs = 0
        placedAgents.clear()
        lastActions.clear()
        lastHud.clear()
        clearDecisionStats()
    }

    fun snapshot(): List<AgentState> = scheduler?.snapshot() ?: agentsBuilder.map { it.first }

    fun currentScheduler(): Scheduler? = scheduler

    /**
     * Update the Bukkit world's pumpkin blocks + carried items + floating HUDs to match the
     * current grid state. If multiple agents share a cell, the lower-id agent sits on the bottom
     * and others stack above.
     */
    private fun render() {
        val p = painter ?: return
        val cur = snapshot()
        val newSlots = HashMap<Int, Slot>(cur.size)
        cur.groupBy { it.pos }
            .forEach { (pos, group) ->
                group.sortedBy { it.id }.forEachIndexed { idx, a -> newSlots[a.id] = Slot(pos, idx) }
            }
        for ((id, old) in placedAgents.toMap()) {
            val newSlot = newSlots[id]
            if (newSlot == null || newSlot != old.slot) {
                p.setCarried(old.slot.pos, null, old.slot.level)
                p.removeAgent(old.slot.pos, old.slot.level)
                placedAgents.remove(id)
            }
            if (newSlot == null) {
                hud.remove(id)
                lastHud.remove(id)
            }
        }
        val agentsById = scheduler?.agents.orEmpty()
        val byId = cur.associateBy { it.id }
        for ((id, slot) in newSlots) {
            val a = byId.getValue(id)
            val prev = placedAgents[id]
            // Only (re)write blocks that actually changed. Re-setting an unchanged
            // pumpkin makes the client re-render it, which reads as a blink for an
            // agent that just waited (e.g. already sitting on its goal).
            if (prev == null || prev.slot != slot) p.placeAgent(slot.pos, slot.level)
            if (prev == null || prev.slot != slot || prev.carried != a.carrying)
                p.setCarried(slot.pos, a.carrying, slot.level)
            placedAgents[id] = Placed(slot, a.carrying)

            val snap = buildHud(a, slot, agentsById[id]?.second)
            if (lastHud[id] != snap) {
                hud.update(snap)
                lastHud[id] = snap
            }
        }
    }

    private fun buildHud(a: AgentState, slot: Slot, brain: Brain?): HudSnapshot {
        val stats = (brain as? InstrumentedBrain)?.stats
        val carryingText = a.carrying?.name?.let { "§e$it" } ?: "§8—"
        val lastText = lastActions[a.id]?.let(::formatAction) ?: "§8—"
        val nodes = stats?.nodesExpanded ?: 0L
        val frontier = stats?.maxFrontier ?: 0L
        val decisionMs = stats?.lastDecisionMs ?: 0L
        val lines = listOf(
            "§e§l${a.brain}§r §7#${a.id}",
            "§7@§f(${a.pos.x},${a.pos.z}) §7→§f${a.facing.name.first()}",
            "§7hold §r$carryingText  §7last §r$lastText",
            "§7nodes §f$nodes  §7frontier §f$frontier",
            "§7decision §f${decisionMs}ms  §7phase §f${phaseFor(a.brain)}",
        )
        // Hologram floats one block above the carried-item slot for the agent's stack level.
        val y = WorldPainter.Y_CARRIED + slot.level * WorldPainter.STACK_STRIDE + HUD_Y_OFFSET
        return HudSnapshot(id = a.id, x = slot.pos.x, y = y, z = slot.pos.z, lines = lines)
    }

    /**
     * Static labels for every meaningful tile — goals, gold, iron, pits and the Wumpus — plus the
     * Stench / Breeze halos the Wumpus and pits cast onto their 4-neighbourhoods. Recomputed on each
     * map load; a cell that catches more than one thing (e.g. a goal sitting in a breeze) gets a
     * stacked multi-line label.
     */
    private fun cellLabels(): List<CellLabel> {
        val stench = HashSet<Pos>()
        val breeze = HashSet<Pos>()
        for (x in 0 until world.width) for (z in 0 until world.height) {
            val pos = Pos(x, z)
            val tags = world.cell(pos).tags
            if (CellTag.WUMPUS in tags)
                for (d in Direction.entries) pos.shift(d).takeIf(world::inBounds)?.let { stench += it }
            if (CellTag.PIT in tags)
                for (d in Direction.entries) pos.shift(d).takeIf(world::inBounds)?.let { breeze += it }
        }
        val labels = ArrayList<CellLabel>()
        for (x in 0 until world.width) for (z in 0 until world.height) {
            val pos = Pos(x, z)
            val lines = buildList {
                blockLabel(world.cell(pos).tags)?.let { add(it) }
                if (pos in stench) add(STENCH_LABEL)
                if (pos in breeze) add(BREEZE_LABEL)
            }
            if (lines.isNotEmpty()) labels += CellLabel(pos.x, pos.z, LABEL_Y, lines)
        }
        return labels
    }

    /** The label for a tile's own contents, or null for a plain floor. */
    private fun blockLabel(tags: Set<CellTag>): String? = when {
        CellTag.WUMPUS in tags -> WUMPUS_LABEL
        CellTag.PIT in tags -> PIT_LABEL
        CellTag.GOLD in tags -> GOLD_LABEL
        CellTag.IRON in tags -> IRON_LABEL
        CellTag.GOAL_RED in tags -> GOAL_RED_LABEL
        CellTag.GOAL_BLUE in tags -> GOAL_BLUE_LABEL
        CellTag.GOAL_GREEN in tags -> GOAL_GREEN_LABEL
        CellTag.GOAL_YELLOW in tags -> GOAL_YELLOW_LABEL
        else -> null
    }

    private fun formatAction(action: Action): String = when (action) {
        is Action.Move -> "§bmove §f${action.dir.name.first()}"
        Action.Pickup -> "§apickup"
        Action.Drop -> "§ddrop"
        Action.Wait -> "§8wait"
    }

    private fun clearDecisionStats() {
        firstNodes.clear()
        firstFrontier.clear()
        firstDecisionMs.clear()
    }

    /** Latch each agent's first node-expanding decision; ignore later, contention-skewed ones. */
    private fun captureDecisionStats() {
        val sched = scheduler ?: return
        for ((id, pair) in sched.agents) {
            if (id in firstNodes) continue
            val stats = (pair.second as? InstrumentedBrain)?.stats ?: continue
            if (stats.nodesExpanded <= 0L) continue
            firstNodes[id] = stats.nodesExpanded
            firstFrontier[id] = stats.maxFrontier
            firstDecisionMs[id] = stats.lastDecisionMs
        }
    }

    fun recordMetrics(outcome: String) {
        val sched = scheduler ?: return
        val agentPairs = sched.agents.values
        if (agentPairs.isEmpty()) return
        val ticks = sched.ticks
        for ((agentState, _) in agentPairs) {
            val id = agentState.id
            metrics.record(
                MetricsRow(
                    phase = phaseFor(agentState.brain),
                    map = currentMapName,
                    brain = agentState.brain,
                    agents = agentPairs.size,
                    seed = seed,
                    ticks = ticks,
                    pathLength = ticks,
                    nodesExpanded = firstNodes[id] ?: 0,
                    maxFrontier = firstFrontier[id] ?: 0,
                    msTotal = totalTickTimeMs,
                    msPerDecision = firstDecisionMs[id] ?: 0,
                    outcome = outcome
                )
            )
        }
    }

    private fun ensureScheduler(): Scheduler? {
        if (scheduler == null && agentsBuilder.isNotEmpty()) {
            scheduler = Scheduler(world, agentsBuilder.toList())
        }
        return scheduler
    }

    private fun newBrain(name: String): Brain = when (name.uppercase()) {
        "REFLEX" -> ReflexBrain()
        "BFS" -> BfsBrain()
        "DFS" -> DfsBrain()
        "UCS" -> UcsBrain()
        "ASTAR", "A_STAR", "A*" -> AStarBrain(Heuristics::manhattan)
        "ASTAR_EUCLID" -> AStarBrain(Heuristics::euclidean)
        "ASTAR_BAD" -> AStarBrain(Heuristics::diagonalOvershoot)
        "MINIMAX" -> MinimaxBrain()
        "ALPHABETA" -> AlphaBetaBrain()
        "PROLOG", "WUMPUS" -> PrologBrain()
        else -> throw IllegalArgumentException("unknown brain: $name")
    }

    private fun phaseFor(brainName: String): String = when (brainName.uppercase()) {
        "REFLEX" -> "0"
        "BFS", "DFS", "UCS", "ASTAR", "ASTAR_EUCLID", "ASTAR_BAD" -> "1"
        "MINIMAX", "ALPHABETA" -> "2"
        "PROLOG", "WUMPUS" -> "3"
        else -> "?"
    }

    companion object {
        /** Quiet enough not to drown out the demonstrator. */
        private const val SOUND_VOLUME = 0.4f

        /** Distance above the carried-block slot at which the floating HUD anchors. */
        private const val HUD_Y_OFFSET = 1.8

        /**
         * World height for the static cell labels. HolographicDisplays hangs its lines *downward*
         * from the anchor, so this sits a couple of blocks above the obstacle layer to keep even a
         * three-line stack clear of the block tops, while staying below the agent HUDs.
         */
        private const val LABEL_Y = WorldPainter.Y_OBSTACLE + 2.5

        private const val WUMPUS_LABEL = "§4§lWUMPUS"
        private const val PIT_LABEL = "§8§lPIT"
        private const val STENCH_LABEL = "§5Stench"
        private const val BREEZE_LABEL = "§bBreeze"
        private const val GOLD_LABEL = "§6§lGold"
        private const val IRON_LABEL = "§7§lIron"
        private const val GOAL_RED_LABEL = "§cGoal"
        private const val GOAL_BLUE_LABEL = "§9Goal"
        private const val GOAL_GREEN_LABEL = "§aGoal"
        private const val GOAL_YELLOW_LABEL = "§eGoal"
    }
}
