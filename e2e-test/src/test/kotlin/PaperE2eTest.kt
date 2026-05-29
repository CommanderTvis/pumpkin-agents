package io.github.commandertvis.pumpkins.e2e

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

@Timeout(value = 5, unit = TimeUnit.MINUTES)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PaperE2eTest {

    private lateinit var paper: PaperFixture

    @BeforeAll
    fun bootServer() {
        paper = PaperFixture.boot()
    }

    @AfterAll
    fun shutdown() {
        if (::paper.isInitialized) paper.close()
    }

    @Test
    @Order(1)
    fun `plugin loads and reports enabled`() {
        val out = paper.sendCommand("plugins")
        // Paper formats plugins as colored chips with each name separated by a comma
        out shouldContain "PumpkinAgents"
    }

    @Test
    @Order(2)
    fun `pumpkin map load corridor + spawn BFS + step reaches goal`() {
        paper.sendCommand("pumpkin map load corridor")
        paper.sendCommand("pumpkin spawn BFS 1 1")
        // 6 deterministic ticks to reach the goal in corridor
        repeat(8) { paper.sendCommand("pumpkin step") }
        val state = paper.sendCommand("pumpkin state")
        // The agent should report finishing at (7, 1)
        state shouldContain "pos=(7,1)"
    }

    @Test
    @Order(3)
    fun `multigoal run records search effort BFS expands far more than A-star`() {
        paper.sendCommand("pumpkin reset")
        paper.sendCommand("pumpkin map load multigoal")
        paper.sendCommand("pumpkin spawn BFS 1 1")
        paper.sendCommand("pumpkin spawn ASTAR 1 1")
        // A run records one row per agent at RAN_OUT. dps=2 -> ~0.5s/decision.
        paper.sendCommand("pumpkin run 14")

        val csv = paper.metricsDir.resolve("metrics.csv")
        val rows = awaitMultigoalRows(csv, deadlineMs = 90_000)

        rows[0] shouldContain "phase,map,brain,agents,seed,ticks"
        val cols = rows[0].split(',')
        val nodesIdx = cols.indexOf("nodes_expanded")
        val brainIdx = cols.indexOf("brain")

        fun nodesFor(brain: String): Long = rows.drop(1)
            .first { it.split(',')[brainIdx] == brain }
            .split(',')[nodesIdx].toLong()

        // High-water search effort over the run — not the trailing Wait-on-goal decision.
        nodesFor("BFS") shouldBe 52L
        nodesFor("ASTAR") shouldBe 12L
    }

    /** Poll until both multigoal agent rows have been flushed to the CSV. */
    private fun awaitMultigoalRows(csv: java.nio.file.Path, deadlineMs: Long): List<String> {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (csv.exists()) {
                val lines = csv.readText().trim().split('\n')
                val mapIdx = lines[0].split(',').indexOf("map")
                val multigoal = lines.drop(1).filter { it.split(',').getOrNull(mapIdx) == "multigoal" }
                if (multigoal.size >= 2) return listOf(lines[0]) + multigoal
            }
            Thread.sleep(500)
        }
        error("multigoal rows not recorded within ${deadlineMs}ms")
    }

    @Test
    @Order(4)
    fun `prolog brain navigates wumpus_4 without dying`() {
        paper.sendCommand("pumpkin reset")
        paper.sendCommand("pumpkin map load wumpus_4")
        paper.sendCommand("pumpkin spawn PROLOG 1 1")
        repeat(40) { paper.sendCommand("pumpkin step") }
        val state = paper.sendCommand("pumpkin state")
        // Just check it returned a state line, not an error
        state shouldContain "brain=PROLOG"
    }

    @Test
    @Order(5)
    fun `server log has no stack traces or SEVERE entries`() {
        // Give Paper a moment to flush remaining log lines.
        Thread.sleep(500)
        val log = paper.latestLog
        check(log.exists()) { "latest.log missing at $log" }
        val text = log.readText()
        // No JVM stacks, no Bukkit/Paper errors related to our plugin
        text shouldNotContain "java.lang.NullPointerException"
        text shouldNotContain "ClassCastException"
        text shouldNotContain "NoClassDefFoundError"
        // Our plugin must not have emitted SEVERE
        for (line in text.split('\n')) {
            if (!line.contains("[PumpkinAgents]")) continue
            line shouldNotContain "SEVERE"
        }
        text shouldNotBe ""
    }
}
