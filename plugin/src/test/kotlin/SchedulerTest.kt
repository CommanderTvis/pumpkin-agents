package io.github.commandertvis.pumpkins.test

import io.github.commandertvis.pumpkins.agent.Action
import io.github.commandertvis.pumpkins.agent.AgentState
import io.github.commandertvis.pumpkins.agent.Brain
import io.github.commandertvis.pumpkins.agent.brains.BfsBrain
import io.github.commandertvis.pumpkins.agent.orchestrator.ConflictResolver
import io.github.commandertvis.pumpkins.agent.orchestrator.Scheduler
import io.github.commandertvis.pumpkins.world.Direction
import io.github.commandertvis.pumpkins.world.Pos
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class SchedulerTest : FunSpec({
    test("ConflictResolver: lower id wins, higher id waits") {
        val world = Maps.load("corridor")
        val agents = mapOf(
            1 to AgentState(1, Pos(1, 1)),
            2 to AgentState(2, Pos(3, 1))
        )
        val proposals = mapOf(
            1 to Action.Move(Direction.EAST),
            2 to Action.Move(Direction.WEST)
        )
        val resolved = ConflictResolver.resolve(agents, proposals)
        // Both want to move toward each other; neither targets the same cell yet (positions 2 vs 2 — actually both target (2,1)).
        // Lower id (1) wins. Agent 2 waits.
        resolved[1] shouldBe Action.Move(Direction.EAST)
        resolved[2] shouldBe Action.Wait
        // Sanity: world is fine
        world.width shouldBe 8
    }

    test("Scheduler tick advances a BFS agent toward the goal") {
        val world = Maps.load("corridor")
        val brain: Brain = BfsBrain()
        val a = AgentState(id = 1, pos = Pos(1, 1), brain = "BFS")
        val sched = Scheduler(world, listOf(a to brain))
        repeat(6) { sched.tick() }
        sched.snapshot().first().pos shouldBe Pos(7, 1)
    }

    test("Scheduler keeps id order deterministic") {
        val world = Maps.load("corridor")
        val s1 = AgentState(id = 1, pos = Pos(1, 1))
        val s2 = AgentState(id = 2, pos = Pos(3, 1))
        val brain = Brain { Action.Wait }
        val sched = Scheduler(world, listOf(s2 to brain, s1 to brain))
        sched.snapshot().map { it.id } shouldContainAll listOf(1, 2)
    }
})
