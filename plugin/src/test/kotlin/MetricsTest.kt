package io.github.commandertvis.pumpkins.test

import io.github.commandertvis.pumpkins.metrics.MetricsLogger
import io.github.commandertvis.pumpkins.metrics.MetricsRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

class MetricsTest : FunSpec({
    test("MetricsLogger writes a CSV header and one row per record") {
        val tmp = createTempDirectory("metrics-test")
        try {
            val logger = MetricsLogger(tmp)
            logger.record(
                MetricsRow(
                    phase = "1", map = "corridor", brain = "BFS",
                    agents = 1, seed = 1234, ticks = 6, pathLength = 6,
                    nodesExpanded = 12, maxFrontier = 4, msTotal = 3,
                    msPerDecision = 1, outcome = "GOAL"
                )
            )
            val csv = tmp.resolve("metrics.csv").readText().trim().split('\n')
            csv.size shouldBe 2
            csv[0] shouldBe MetricsLogger.HEADER
            csv[1] shouldContain "corridor"
            csv[1] shouldContain "BFS"
            csv[1] shouldContain "GOAL"
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("CSV columns match the documented order") {
        val cols = MetricsLogger.HEADER.split(",")
        cols shouldContainAll listOf(
            "timestamp", "phase", "map", "brain", "agents", "seed", "ticks",
            "path_length", "nodes_expanded", "max_frontier",
            "ms_total", "ms_per_decision", "outcome"
        )
    }
})
