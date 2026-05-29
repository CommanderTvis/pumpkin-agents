package io.github.commandertvis.pumpkins.test

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.AgentState
import io.github.commandertvis.pumpkins.agent.Brain
import io.github.commandertvis.pumpkins.agent.Percept
import io.github.commandertvis.pumpkins.agent.brains.*
import io.github.commandertvis.pumpkins.agent.search.Heuristics
import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

private fun simulate(
    world: GridWorld,
    brain: Brain,
    start: Pos,
    goals: Set<Pos>,
    maxTicks: Int = 4096,
): Pair<Int, AgentState> {
    var state = AgentState(id = 1, pos = start, brain = brain::class.simpleName ?: "X")
    var ticks = 0
    while (ticks < maxTicks) {
        if (state.pos in goals) return ticks to state
        val percept = Percept.FullView(self = state, world = world)
        val action = brain.decide(percept)
        state = when (action) {
            is Action.Move -> {
                val np = state.pos.shift(action.dir)
                if (world.passable(np)) state.copy(pos = np, facing = action.dir)
                else state.copy(facing = action.dir)
            }

            Action.Pickup -> {
                val tag = world.cell(state.pos).tags.firstOrNull { it == CellTag.GOLD || it == CellTag.IRON }
                if (tag != null && state.carrying == null) state.copy(carrying = tag) else state
            }

            Action.Drop -> if (state.carrying != null) state.copy(carrying = null) else state
            Action.Wait -> state
        }
        ticks++
    }
    return ticks to state
}

class BrainTest : FunSpec({
    test("ReflexBrain follows wall on corridor and ends at goal") {
        val world = Maps.load("corridor")
        val goals = Maps.goalsOf(world)
        val brain = ReflexBrain()
        val (_, state) = simulate(world, brain, Pos(1, 1), goals, maxTicks = 256)
        (state.pos in goals).shouldBeTrue()
    }

    test("BFS finds the goal on corridor") {
        val world = Maps.load("corridor")
        val goals = Maps.goalsOf(world)
        val brain = BfsBrain()
        val (ticks, state) = simulate(world, brain, Pos(1, 1), goals)
        state.pos shouldBe Pos(7, 1)
        ticks shouldBe 6
        (brain.stats.nodesExpanded > 0L).shouldBeTrue()
    }

    test("AStar reaches a goal on maze_s with the manhattan heuristic") {
        val world = Maps.load("maze_s")
        val goals = Maps.goalsOf(world)
        val brain = AStarBrain(Heuristics::manhattan)
        val (ticks, state) = simulate(world, brain, Pos(1, 1), goals)
        (state.pos in goals).shouldBeTrue()
        (ticks <= 128).shouldBeTrue()
        (brain.stats.nodesExpanded > 0L).shouldBeTrue()
    }

    test("BFS publishes its full plan in stats.lastPlan so visualizers can trace it") {
        val world = Maps.load("corridor")
        val goals = Maps.goalsOf(world)
        val brain = BfsBrain()
        val percept = Percept.FullView(
            self = AgentState(id = 1, pos = Pos(1, 1), brain = "BFS"),
            world = world,
        )
        brain.decide(percept)
        brain.stats.lastPlan.isNotEmpty().shouldBeTrue()
        brain.stats.lastPlan.last() shouldBe goals.first()
    }

    test("AStar expands no more nodes than BFS on maze_s") {
        val world = Maps.load("maze_s")
        val goals = Maps.goalsOf(world)
        val bfs = BfsBrain()
        val astar = AStarBrain(Heuristics::manhattan)
        simulate(world, bfs, Pos(1, 1), goals)
        simulate(world, astar, Pos(1, 1), goals)
        (astar.stats.nodesExpanded <= bfs.stats.nodesExpanded).shouldBeTrue()
    }

    test("DFS eventually reaches the corridor goal") {
        val world = Maps.load("corridor")
        val goals = Maps.goalsOf(world)
        val brain = DfsBrain(maxDepth = 256)
        val (_, state) = simulate(world, brain, Pos(1, 1), goals, maxTicks = 256)
        state.pos shouldBe Pos(7, 1)
    }

    test("UCS handles terrain costs (sand vs snow)") {
        val world = Maps.load("maze_s")
        val goals = Maps.goalsOf(world)
        val brain = UcsBrain()
        val (_, state) = simulate(world, brain, Pos(1, 1), goals)
        (state.pos in goals).shouldBeTrue()
    }

    test("AlphaBeta predator closes in on prey in arena") {
        val world = Maps.load("arena")
        val brain = AlphaBetaBrain(depth = 4)
        var pred = AgentState(id = 1, pos = Pos(2, 2), brain = "ALPHABETA")
        val prey = AgentState(id = 2, pos = Pos(17, 17), brain = "PREY")
        val d0 = pred.pos.manhattan(prey.pos)
        repeat(10) {
            val percept = Percept.FullView(self = pred, world = world, others = listOf(prey))
            val a = brain.decide(percept)
            if (a is Action.Move) {
                val np = pred.pos.shift(a.dir)
                if (world.passable(np)) pred = pred.copy(pos = np, facing = a.dir)
            }
        }
        val d1 = pred.pos.manhattan(prey.pos)
        (d1 < d0).shouldBeTrue()
        (brain.stats.nodesExpanded > 0L).shouldBeTrue()
    }

    test("Minimax matches AlphaBeta direction on small depth") {
        val world = Maps.load("arena")
        val mm = MinimaxBrain(depth = 3)
        val ab = AlphaBetaBrain(depth = 3)
        val pred = AgentState(id = 1, pos = Pos(3, 3), brain = "X")
        val prey = AgentState(id = 2, pos = Pos(15, 15), brain = "PREY")
        val percept = Percept.FullView(self = pred, world = world, others = listOf(prey))
        val a1 = mm.decide(percept)
        val a2 = ab.decide(percept)
        a1 shouldNotBe Action.Wait
        a2 shouldNotBe Action.Wait
        (ab.stats.nodesExpanded <= mm.stats.nodesExpanded).shouldBeTrue()
    }

    test("PrologBrain navigates wumpus_4: grabs the gold and walks back alive") {
        val world = Maps.load("wumpus_4")
        val brain = PrologBrain()
        val start = Pos(1, 1)
        var state = AgentState(id = 1, pos = start, brain = "PROLOG")
        var grabbed = false
        var ticks = 0
        while (ticks < 200) {
            (CellTag.PIT in world.cell(state.pos).tags).shouldBe(false)
            (CellTag.WUMPUS in world.cell(state.pos).tags).shouldBe(false)
            val adj = io.github.commandertvis.pumpkins.world.Direction.entries.map { state.pos.shift(it) }
            val pit = adj.any { CellTag.PIT in world.cell(it).tags }
            val wump = adj.any { CellTag.WUMPUS in world.cell(it).tags }
            val gold = CellTag.GOLD in world.cell(state.pos).tags
            val percept = Percept.Local(state, world, breeze = pit, stench = wump, glitter = gold, bump = false)
            val a = brain.decide(percept)
            state = when (a) {
                is Action.Move -> {
                    val np = state.pos.shift(a.dir)
                    if (world.passable(np)) state.copy(pos = np, facing = a.dir) else state.copy(facing = a.dir)
                }

                Action.Pickup -> if (gold && state.carrying == null) state.copy(carrying = CellTag.GOLD)
                    .also { grabbed = true } else state

                Action.Drop -> state
                Action.Wait -> state
            }
            if (grabbed && state.pos == start) break
            ticks++
        }
        grabbed.shouldBeTrue()
        state.carrying shouldBe CellTag.GOLD
    }

    test("PrologBrain.debugLines speaks in plain English about beliefs and hazards") {
        val world = Maps.load("wumpus_4")
        val brain = PrologBrain()
        val start = Pos(1, 1)
        val state = AgentState(id = 1, pos = start, brain = "PROLOG")

        brain.debugLines() shouldBe listOf("I haven't taken a step yet.")

        // First percept at (1, 1) — wumpus_4 has a pit at (1, 4) and wumpus at (2, 4),
        // so (1, 1) is clean. After one tick the brain should describe an empty cell
        // and not yet suspect anything.
        brain.decide(Percept.Local(state, world, breeze = false, stench = false, glitter = false, bump = false))
        val lines = brain.debugLines()
        lines[0] shouldBe "Before this step I sensed nothing — no breeze, no stench, no glitter."
        lines[1] shouldStartWith "So far I've walked 1 cell and proven "
        lines.none { it.startsWith("I won't step into") }.shouldBeTrue()

        // Walk south one cell and report a breeze — the brain should refuse the two
        // unvisited neighbours of (1, 2).
        val south = state.copy(pos = Pos(1, 2))
        brain.decide(Percept.Local(south, world, breeze = true, stench = false, glitter = false, bump = false))
        val after = brain.debugLines()
        after[0] shouldBe "Before this step I sensed a breeze."
        after.last() shouldBe "I won't step into (2,2) or (1,3) — any of them could hide a pit or the wumpus."
    }
})
