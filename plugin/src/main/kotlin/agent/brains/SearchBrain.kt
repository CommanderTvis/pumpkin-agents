package io.github.commandertvis.pumpkins.agent.brains

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.BrainStats
import io.github.commandertvis.pumpkins.agent.InstrumentedBrain
import io.github.commandertvis.pumpkins.agent.Percept
import io.github.commandertvis.pumpkins.agent.search.GridPathProblem
import io.github.commandertvis.pumpkins.agent.search.PosHeuristic
import io.github.commandertvis.pumpkins.world.Pos
import java.util.*

/** Shared frontier-based graph search; subclasses pick the queue order. */
abstract class SearchBrain : InstrumentedBrain {
    override val stats: BrainStats = BrainStats()

    final override fun decide(percept: Percept): Action {
        val started = System.nanoTime()
        stats.reset()
        val pv = percept as? Percept.FullView ?: return Action.Wait
        val goals = pv.goalPositions().toSet()
        if (goals.isEmpty() || pv.self.pos in goals) return Action.Wait
        val blocked = pv.others.map { it.pos }.toSet() - pv.self.pos
        val problem = GridPathProblem(pv.world, pv.self.pos, goals, blocked)
        val plan = search(problem, pv) ?: run {
            stats.lastDecisionMs = (System.nanoTime() - started) / 1_000_000
            return Action.Wait
        }
        stats.lastPlan = projectPlan(pv.self.pos, plan)
        stats.lastDecisionMs = (System.nanoTime() - started) / 1_000_000
        return plan.firstOrNull() ?: Action.Wait
    }

    private fun projectPlan(start: Pos, actions: List<Action>): List<Pos> {
        if (actions.isEmpty()) return emptyList()
        val out = ArrayList<Pos>(actions.size)
        var p = start
        for (a in actions) {
            if (a is Action.Move) {
                p = p.shift(a.dir)
                out += p
            }
        }
        return out
    }

    protected abstract fun search(problem: GridPathProblem, percept: Percept.FullView): List<Action>?
}

private data class Node(val state: Pos, val parent: Node?, val action: Action?, val g: Int)

private fun reconstruct(node: Node): List<Action> {
    val out = ArrayDeque<Action>()
    var n: Node? = node
    while (n?.parent != null) {
        n.action?.let(out::addFirst)
        n = n.parent
    }
    return out.toList()
}

class BfsBrain : SearchBrain() {
    override fun search(problem: GridPathProblem, percept: Percept.FullView): List<Action>? {
        val start = Node(problem.initial, null, null, 0)
        if (problem.isGoal(start.state)) return emptyList()
        val frontier = ArrayDeque<Node>().apply { add(start) }
        val explored = hashSetOf(start.state)
        while (frontier.isNotEmpty()) {
            stats.maxFrontier = maxOf(stats.maxFrontier, frontier.size.toLong())
            val node = frontier.pollFirst()
            stats.nodesExpanded++
            for (a in problem.actions(node.state)) {
                val next = problem.result(node.state, a)
                if (next in explored) continue
                val child = Node(next, node, a, node.g + 1)
                if (problem.isGoal(next)) return reconstruct(child)
                explored += next
                frontier.addLast(child)
            }
        }
        return null
    }
}

class DfsBrain(private val maxDepth: Int = 4096) : SearchBrain() {
    override fun search(problem: GridPathProblem, percept: Percept.FullView): List<Action>? {
        val start = Node(problem.initial, null, null, 0)
        if (problem.isGoal(start.state)) return emptyList()
        val stack = ArrayDeque<Node>().apply { addLast(start) }
        val visited = hashSetOf<Pos>()
        while (stack.isNotEmpty()) {
            stats.maxFrontier = maxOf(stats.maxFrontier, stack.size.toLong())
            val node = stack.pollLast()
            if (node.state in visited) continue
            visited += node.state
            stats.nodesExpanded++
            if (problem.isGoal(node.state)) return reconstruct(node)
            if (node.g >= maxDepth) continue

            for (a in problem.actions(node.state).reversed()) {
                val next = problem.result(node.state, a)
                if (next in visited) continue
                stack.addLast(Node(next, node, a, node.g + 1))
            }
        }
        return null
    }
}

class UcsBrain : SearchBrain() {
    override fun search(problem: GridPathProblem, percept: Percept.FullView): List<Action>? {
        val start = Node(problem.initial, null, null, 0)
        val frontier = PriorityQueue<Node>(compareBy { it.g })
        frontier += start
        val best = hashMapOf(start.state to 0)

        while (frontier.isNotEmpty()) {
            stats.maxFrontier = maxOf(stats.maxFrontier, frontier.size.toLong())
            val node = frontier.poll()

            if (problem.isGoal(node.state))
                return reconstruct(node)

            if (node.g > (best[node.state] ?: Int.MAX_VALUE))
                continue

            stats.nodesExpanded++

            for (a in problem.actions(node.state)) {
                val next = problem.result(node.state, a)
                val g = node.g + problem.stepCost(node.state, a, next)

                if (g < (best[next] ?: Int.MAX_VALUE)) {
                    best[next] = g
                    frontier += Node(next, node, a, g)
                }
            }
        }

        return null
    }
}

class AStarBrain(private val heuristicFactory: (Collection<Pos>) -> PosHeuristic) : SearchBrain() {
    override fun search(problem: GridPathProblem, percept: Percept.FullView): List<Action>? {
        val goals = percept.goalPositions()
        val h = heuristicFactory(goals)
        val start = Node(problem.initial, null, null, 0)
        val frontier = PriorityQueue<Pair<Int, Node>>(compareBy { it.first })
        frontier += h(start.state) to start
        val best = hashMapOf(start.state to 0)

        while (frontier.isNotEmpty()) {
            stats.maxFrontier = maxOf(stats.maxFrontier, frontier.size.toLong())
            val (_, node) = frontier.poll()
            if (problem.isGoal(node.state)) return reconstruct(node)
            if (node.g > (best[node.state] ?: Int.MAX_VALUE)) continue
            stats.nodesExpanded++
            for (a in problem.actions(node.state)) {
                val next = problem.result(node.state, a)
                val g = node.g + problem.stepCost(node.state, a, next)
                if (g < (best[next] ?: Int.MAX_VALUE)) {
                    best[next] = g
                    frontier += (g + h(next)) to Node(next, node, a, g)
                }
            }
        }

        return null
    }
}
