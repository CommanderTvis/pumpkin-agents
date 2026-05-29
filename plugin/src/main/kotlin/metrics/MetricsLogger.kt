package io.github.commandertvis.pumpkins.metrics

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.writeText

data class MetricsRow(
    val phase: String,
    val map: String,
    val brain: String,
    val agents: Int,
    val seed: Long,
    val ticks: Long,
    val pathLength: Long,
    val nodesExpanded: Long,
    val maxFrontier: Long,
    val msTotal: Long,
    val msPerDecision: Long,
    val outcome: String,
)

class MetricsLogger(private val outputDir: Path) {
    private val rows = mutableListOf<MetricsRow>()

    init {
        outputDir.createDirectories()
    }

    fun record(row: MetricsRow) {
        rows += row
        appendCsv(row)
    }

    fun summary(): String {
        if (rows.isEmpty()) return "(no runs recorded yet)"
        val header = "phase  map         brain      agents ticks path nodes  frontier msTotal ms/dec outcome"
        val body = rows.joinToString("\n") {
            "%-6s %-11s %-10s %6d %5d %4d %5d %8d %7d %6d %s".format(
                it.phase, it.map, it.brain, it.agents, it.ticks, it.pathLength,
                it.nodesExpanded, it.maxFrontier, it.msTotal, it.msPerDecision, it.outcome
            )
        }
        return "$header\n$body"
    }

    private fun appendCsv(row: MetricsRow) {
        val file = outputDir.resolve("metrics.csv")
        val line = listOf(
            Instant.now(), row.phase, row.map, row.brain, row.agents, row.seed,
            row.ticks, row.pathLength, row.nodesExpanded, row.maxFrontier,
            row.msTotal, row.msPerDecision, row.outcome
        ).joinToString(",") + "\n"

        if (file.notExists()) file.writeText(HEADER + "\n" + line) else file.appendText(line)
    }

    companion object {
        const val HEADER =
            "timestamp,phase,map,brain,agents,seed,ticks,path_length,nodes_expanded,max_frontier,ms_total,ms_per_decision,outcome"
    }
}
