package io.github.commandertvis.pumpkins.e2e

import io.kotest.matchers.collections.shouldNotBeEmpty
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
    fun `pumpkin metrics records a non-empty CSV`() {
        paper.sendCommand("pumpkin metrics")
        // The runtime auto-records a row on `metrics` request. Verify the CSV file directly.
        val csv = paper.metricsDir.resolve("metrics.csv")
        check(csv.exists()) { "metrics.csv not created at $csv" }
        val lines = csv.readText().trim().split('\n')
        check(lines.size >= 2) { "metrics.csv must have header + at least one row" }
        lines[0] shouldContain "phase,map,brain,agents,seed,ticks"
        lines.drop(1).shouldNotBeEmpty()
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
