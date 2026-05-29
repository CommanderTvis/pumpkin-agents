package io.github.commandertvis.pumpkins.agent

import io.github.commandertvis.pumpkins.world.Pos

fun interface Brain {
    fun decide(percept: Percept): Action
}

/** Snapshot of expansion stats, written by search brains for the
 * [io.github.commandertvis.pumpkins.metrics.MetricsLogger]. */
data class BrainStats(
    var nodesExpanded: Long = 0L,
    var maxFrontier: Long = 0L,
    var lastDecisionMs: Long = 0L,
    /** Positions the brain plans to walk through, in order, starting *after* its current cell. */
    var lastPlan: List<Pos> = emptyList(),
) {
    fun reset() {
        nodesExpanded = 0
        maxFrontier = 0
        lastDecisionMs = 0
        lastPlan = emptyList()
    }
}

interface InstrumentedBrain : Brain {
    val stats: BrainStats
}

/** Optional view onto a brain's internal beliefs, rendered by `/pumpkin state`. */
interface Introspectable {
    fun debugLines(): List<String>
}
