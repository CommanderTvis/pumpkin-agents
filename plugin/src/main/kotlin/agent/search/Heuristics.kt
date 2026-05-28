package io.github.commandertvis.pumpkins.agent.search

import io.github.commandertvis.pumpkins.world.Pos
import kotlin.math.abs
import kotlin.math.hypot

typealias PosHeuristic = (Pos) -> Int

object Heuristics {
    fun manhattan(goals: Collection<Pos>): PosHeuristic = { p ->
        goals.minOfOrNull { abs(p.x - it.x) + abs(p.z - it.z) } ?: 0
    }

    fun euclidean(goals: Collection<Pos>): PosHeuristic = { p ->
        goals.minOfOrNull { hypot((p.x - it.x).toDouble(), (p.z - it.z).toDouble()).toInt() } ?: 0
    }

    /** Deliberately inadmissible — for the Phase 1 heuristic comparison. */
    fun diagonalOvershoot(goals: Collection<Pos>): PosHeuristic = { p ->
        val base = goals.minOfOrNull { abs(p.x - it.x) + abs(p.z - it.z) } ?: 0
        (base * 3) / 2
    }
}
