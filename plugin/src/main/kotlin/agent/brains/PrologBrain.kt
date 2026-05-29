package io.github.commandertvis.pumpkins.agent.brains

import io.github.commandertvis.pumpkins.agent.*
import io.github.commandertvis.pumpkins.world.CellTag
import io.github.commandertvis.pumpkins.world.Direction
import io.github.commandertvis.pumpkins.world.GridWorld
import io.github.commandertvis.pumpkins.world.Pos
import it.unibo.tuprolog.core.Integer
import it.unibo.tuprolog.core.Struct
import it.unibo.tuprolog.core.Var
import it.unibo.tuprolog.dsl.theory.logicProgramming
import it.unibo.tuprolog.solve.Solver
import it.unibo.tuprolog.solve.classic.stdlib.DefaultBuiltins
import it.unibo.tuprolog.solve.library.Runtime
import it.unibo.tuprolog.theory.Theory
import java.util.*

/**
 * Knowledge-based Wumpus-world agent backed by [2p-kt](https://github.com/tuProlog/2p-kt).
 *
 * After each percept we rebuild a small dynamic KB of `visited/2`, `breeze/2`, `stench/2`
 * facts and query the static rule
 * ```
 * safe(X, Z) :- visited(X, Z).
 * safe(X, Z) :- visited(VX, VZ), \+ breeze(VX, VZ), \+ stench(VX, VZ), adjacent(VX, VZ, X, Z).
 * ```
 * — a textbook closed-world inference. Graph search over the resulting safe cells stays in
 * Kotlin because the spec only asks for Prolog at the deduction step.
 */
class PrologBrain : InstrumentedBrain, Introspectable {
    override val stats: BrainStats = BrainStats()

    private val visited = linkedSetOf<Pos>()
    private val breezes = HashSet<Pos>()
    private val stenches = HashSet<Pos>()
    private val knownSafe = HashSet<Pos>()
    private val possibleHazards = HashSet<Pos>()
    private var lastLocal: Percept.Local? = null

    override fun decide(percept: Percept): Action {
        val started = System.nanoTime()
        stats.reset()

        val local = when (percept) {
            is Percept.Local -> percept
            is Percept.FullView -> synthesise(percept)
        }

        lastLocal = local
        tellLocal(local)
        recomputeSafety(local.world)
        val action = chooseAction(local)
        stats.lastDecisionMs = (System.nanoTime() - started) / 1_000_000
        return action
    }

    override fun debugLines(): List<String> = buildList {
        val p = lastLocal ?: run {
            add("I haven't taken a step yet.")
            return@buildList
        }
        add(perceptSentence(p))
        add(beliefSentence())
        hazardSentence()?.let(::add)
    }

    private fun perceptSentence(p: Percept.Local): String {
        val sensed = buildList {
            if (p.breeze) add("a breeze")
            if (p.stench) add("a stench")
            if (p.glitter) add("glitter")
        }
        return if (sensed.isEmpty()) "I sense nothing here — no breeze, no stench, no glitter."
        else "I sense ${joinWith(sensed, "and")} here."
    }

    private fun beliefSentence(): String {
        val walked = pluralize(visited.size, "cell")
        val proven = pluralize(knownSafe.size, "cell")
        val frontier = knownSafe.count { it !in visited }
        if (frontier == 0)
            return "So far I've walked $walked and proven $proven safe, with nothing new left to explore."
        val tail = if (frontier == 1) "1 of them is still unexplored." else "$frontier of them are still unexplored."
        return "So far I've walked $walked and proven $proven safe; $tail"
    }

    private fun hazardSentence(): String? {
        if (possibleHazards.isEmpty()) return null
        val coords = possibleHazards
            .sortedWith(compareBy({ it.z }, { it.x }))
            .map { "(${it.x},${it.z})" }
        return if (coords.size == 1)
            "I won't step into ${coords[0]} — it might hide a pit or the wumpus."
        else
            "I won't step into ${joinWith(coords, "or")} — any of them could hide a pit or the wumpus."
    }

    private fun pluralize(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun joinWith(items: List<String>, conj: String): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} $conj ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", $conj ${items.last()}"
    }

    private fun synthesise(p: Percept.FullView): Percept.Local {
        val pos = p.self.pos
        val adj = Direction.entries.map { pos.shift(it) }
        val pit = adj.any { CellTag.PIT in p.world.cell(it).tags }
        val wumpus = adj.any { CellTag.WUMPUS in p.world.cell(it).tags }
        val gold = CellTag.GOLD in p.world.cell(pos).tags
        return Percept.Local(p.self, p.world, breeze = pit, stench = wumpus, glitter = gold, bump = false)
    }

    private fun tellLocal(p: Percept.Local) {
        val here = p.self.pos
        visited += here
        if (p.breeze) breezes += here
        if (p.stench) stenches += here
    }

    private fun recomputeSafety(world: GridWorld) {
        val solver = Solver.prolog.solverOf(
            libraries = Runtime.of(DefaultBuiltins),
            staticKb = staticRules(),
            dynamicKb = factsFromPercepts(),
        )

        val xVar = Var.of("X")
        val zVar = Var.of("Z")
        val solutions = solver.solveList(Struct.of("safe", xVar, zVar))
        stats.nodesExpanded += solutions.size.toLong()
        knownSafe.clear()

        for (sol in solutions) {
            if (!sol.isYes) continue
            val sub = sol.substitution
            val xt = sub[xVar] as? Integer ?: continue
            val zt = sub[zVar] as? Integer ?: continue
            val p = Pos(xt.value.toInt(), zt.value.toInt())
            if (world.inBounds(p) && world.passable(p)) knownSafe += p
        }

        possibleHazards.clear()

        for (b in breezes + stenches) {
            for (d in Direction.entries) {
                val n = b.shift(d)
                if (!world.inBounds(n) || !world.passable(n)) continue
                if (n !in knownSafe) possibleHazards += n
            }
        }
    }

    private fun factsFromPercepts(): Theory = logicProgramming {
        theoryOf(
            buildList {
                for (v in visited) add(factOf(structOf("visited", v.x, v.z)))
                for (b in breezes) add(factOf(structOf("breeze", b.x, b.z)))
                for (s in stenches) add(factOf(structOf("stench", s.x, s.z)))
            }
        )
    }

    private fun chooseAction(p: Percept.Local): Action {
        val here = p.self.pos
        if (p.glitter && p.self.carrying == null) return Action.Pickup
        if (p.self.carrying == CellTag.GOLD) {
            val start = visited.firstOrNull() ?: here
            return shortestSafePath(here, setOf(start), p.world).firstOrNull() ?: Action.Wait
        }
        val candidates = Direction.entries.mapNotNull { d ->
            val n = here.shift(d)
            if (n in knownSafe && n !in visited && p.world.passable(n)) Action.Move(d) else null
        }
        if (candidates.isNotEmpty()) return candidates.first()
        val frontier = knownSafe.filter { it !in visited }.toSet()
        if (frontier.isNotEmpty()) {
            val plan = shortestSafePath(here, frontier, p.world)
            if (plan.isNotEmpty()) return plan.first()
        }
        return Action.Wait
    }

    private fun shortestSafePath(start: Pos, goals: Set<Pos>, world: GridWorld): List<Action> {
        if (start in goals) return emptyList()
        data class Node(val pos: Pos, val parent: Node?, val act: Action?)

        val q = ArrayDeque<Node>().apply { add(Node(start, null, null)) }
        val seen = hashSetOf(start)

        while (q.isNotEmpty()) {
            val n = q.pollFirst()

            for (d in Direction.entries) {
                val np = n.pos.shift(d)
                if (np in seen) continue
                if (!world.passable(np)) continue
                if (np !in knownSafe) continue
                val child = Node(np, n, Action.Move(d))

                if (np in goals) {
                    val out = ArrayDeque<Action>()
                    var cur: Node? = child
                    while (cur?.parent != null) {
                        cur.act?.let(out::addFirst)
                        cur = cur.parent
                    }
                    return out.toList()
                }

                seen += np
                q += child
            }
        }
        return emptyList()
    }

    private fun staticRules(): Theory = logicProgramming {
        // `\+ breeze(...)` over a predicate with zero clauses raises an `existence_error` warning;
        // the fail-clauses for each percept give the solver definitions to fall through cleanly.
        theory(
            { ruleOf(structOf("visited", anonymous(), anonymous()), atomOf("fail")) },
            { ruleOf(structOf("breeze", anonymous(), anonymous()), atomOf("fail")) },
            { ruleOf(structOf("stench", anonymous(), anonymous()), atomOf("fail")) },
            // safe(X, Z) :- visited(X, Z).
            { structOf("safe", varOf("X"), varOf("Z")) impliedBy structOf("visited", varOf("X"), varOf("Z")) },
            // safe(X, Z) :- visited(VX, VZ), \+ breeze(VX, VZ), \+ stench(VX, VZ), adjacent(VX, VZ, X, Z).
            {
                structOf("safe", varOf("X"), varOf("Z")).impliedBy(
                    structOf("visited", varOf("VX"), varOf("VZ")),
                    naf(structOf("breeze", varOf("VX"), varOf("VZ"))),
                    naf(structOf("stench", varOf("VX"), varOf("VZ"))),
                    structOf("adjacent", varOf("VX"), varOf("VZ"), varOf("X"), varOf("Z")),
                )
            },
            // adjacent/4 — four shifts, one rule each.
            {
                structOf("adjacent", varOf("X"), varOf("Z"), varOf("X1"), varOf("Z")) impliedBy
                        (varOf("X1") `is` (varOf("X") + 1))
            },
            {
                structOf("adjacent", varOf("X"), varOf("Z"), varOf("X1"), varOf("Z")) impliedBy
                        (varOf("X1") `is` (varOf("X") - 1))
            },
            {
                structOf("adjacent", varOf("X"), varOf("Z"), varOf("X"), varOf("Z1")) impliedBy
                        (varOf("Z1") `is` (varOf("Z") + 1))
            },
            {
                structOf("adjacent", varOf("X"), varOf("Z"), varOf("X"), varOf("Z1")) impliedBy
                        (varOf("Z1") `is` (varOf("Z") - 1))
            },
        )
    }
}
