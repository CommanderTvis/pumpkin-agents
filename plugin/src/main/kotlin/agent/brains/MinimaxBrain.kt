package io.github.commandertvis.pumpkins.agent.brains

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.BrainStats
import io.github.commandertvis.pumpkins.agent.InstrumentedBrain
import io.github.commandertvis.pumpkins.agent.Percept
import io.github.commandertvis.pumpkins.world.Direction
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos
import kotlin.math.abs

/**
 * Pumpkin-Tag predator. State = (predator, prey). Evaluation = -manhattan(predator, prey).
 * The predator maximises; the prey minimises.
 */
class MinimaxBrain(private val depth: Int = 4) : InstrumentedBrain {
    override val stats: BrainStats = BrainStats()

    override fun decide(percept: Percept): Action {
        stats.reset()
        val started = System.nanoTime()
        val pv = percept as? Percept.FullView ?: return Action.Wait
        val prey = pv.others.firstOrNull() ?: return Action.Wait
        val (_, bestAction) = max(pv.world, pv.self.pos, prey.pos, depth)
        stats.lastDecisionMs = (System.nanoTime() - started) / 1_000_000
        return bestAction ?: Action.Wait
    }

    private fun evaluate(pred: Pos, prey: Pos): Int = -(abs(pred.x - prey.x) + abs(pred.z - prey.z))

    private fun moves(world: GridWorld, p: Pos): List<Pair<Action, Pos>> =
        Direction.entries.mapNotNull { d ->
            val n = p.shift(d)
            if (world.passable(n)) Action.Move(d) to n else null
        }.ifEmpty { listOf(Action.Wait to p) }

    private fun max(world: GridWorld, pred: Pos, prey: Pos, d: Int): Pair<Int, Action?> {
        stats.nodesExpanded++
        if (pred == prey) return Int.MAX_VALUE / 2 to null
        if (d == 0) return evaluate(pred, prey) to null
        var bestScore = Int.MIN_VALUE
        var bestAction: Action? = null
        for ((a, np) in moves(world, pred)) {
            val (s, _) = min(world, np, prey, d - 1)
            if (s > bestScore) {
                bestScore = s; bestAction = a
            }
        }
        return bestScore to bestAction
    }

    private fun min(world: GridWorld, pred: Pos, prey: Pos, d: Int): Pair<Int, Action?> {
        stats.nodesExpanded++
        if (pred == prey) return Int.MAX_VALUE / 2 to null
        if (d == 0) return evaluate(pred, prey) to null
        var bestScore = Int.MAX_VALUE
        var bestAction: Action? = null
        for ((a, np) in moves(world, prey)) {
            val (s, _) = max(world, pred, np, d - 1)
            if (s < bestScore) {
                bestScore = s; bestAction = a
            }
        }
        return bestScore to bestAction
    }
}

class AlphaBetaBrain(private val depth: Int = 6) : InstrumentedBrain {
    override val stats: BrainStats = BrainStats()

    override fun decide(percept: Percept): Action {
        stats.reset()
        val started = System.nanoTime()
        val pv = percept as? Percept.FullView ?: return Action.Wait
        val prey = pv.others.firstOrNull() ?: return Action.Wait
        val (_, a) = max(pv.world, pv.self.pos, prey.pos, depth, Int.MIN_VALUE, Int.MAX_VALUE)
        stats.lastDecisionMs = (System.nanoTime() - started) / 1_000_000
        return a ?: Action.Wait
    }

    private fun evaluate(pred: Pos, prey: Pos): Int = -(abs(pred.x - prey.x) + abs(pred.z - prey.z))

    private fun moves(world: GridWorld, p: Pos): List<Pair<Action, Pos>> = Direction.entries
        .mapNotNull { d ->
            val n = p.shift(d)
            if (world.passable(n)) Action.Move(d) to n else null
        }
        .ifEmpty { listOf(Action.Wait to p) }

    private fun max(world: GridWorld, pred: Pos, prey: Pos, d: Int, a0: Int, b0: Int): Pair<Int, Action?> {
        stats.nodesExpanded++

        if (pred == prey)
            return Int.MAX_VALUE / 2 to null

        if (d == 0)
            return evaluate(pred, prey) to null

        var alpha = a0
        var bestScore = Int.MIN_VALUE
        var bestAction: Action? = null

        for ((mv, np) in moves(world, pred)) {
            val (s, _) = min(world, np, prey, d - 1, alpha, b0)

            if (s > bestScore) {
                bestScore = s
                bestAction = mv
            }

            if (bestScore >= b0) return bestScore to bestAction
            alpha = maxOf(alpha, bestScore)
        }
        return bestScore to bestAction
    }

    private fun min(world: GridWorld, pred: Pos, prey: Pos, d: Int, a0: Int, b0: Int): Pair<Int, Action?> {
        stats.nodesExpanded++
        if (pred == prey) return Int.MAX_VALUE / 2 to null
        if (d == 0) return evaluate(pred, prey) to null
        var beta = b0
        var bestScore = Int.MAX_VALUE
        var bestAction: Action? = null

        for ((mv, np) in moves(world, prey)) {
            val (s, _) = max(world, pred, np, d - 1, a0, beta)
            if (s < bestScore) {
                bestScore = s; bestAction = mv
            }
            if (bestScore <= a0) return bestScore to bestAction
            beta = minOf(beta, bestScore)
        }

        return bestScore to bestAction
    }
}
