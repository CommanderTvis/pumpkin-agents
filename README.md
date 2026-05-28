# Pumpkin Agents

A Minecraft (Paper 1.19.4) plugin for the ACS-204 *Artificial Intelligence*
course at Constructor University Bremen. Each carved pumpkin on a flat world is
an autonomous agent: it senses the cells around it, decides with one AI method
from the syllabus, and acts &mdash; hopping one block per scheduler step, or picking
up the block above it. The world is both the simulator and the renderer.

## Brains

One brain ships per phase of the syllabus. Phase 0 (situated) is `ReflexBrain`,
a right-hand wall follower. Phase 1 (search) covers `BfsBrain`, `DfsBrain`,
`UcsBrain`, and `AStarBrain` (Manhattan, Euclidean, and a deliberately
inadmissible heuristic). Phase 2 (adversarial) pits `MinimaxBrain` and
`AlphaBetaBrain` against each other in Pumpkin Tag. Phase 3 (reasoning) is
`PrologBrain`, which solves a Wumpus world with real Prolog inference on
[2p-kt](https://github.com/tuProlog/2p-kt).

Source lives under `plugin/src/main/kotlin/agent/brains/`.

## Run it

The Gradle `run` task self-provisions everything &mdash; it downloads the pinned Paper
1.19.4 build, builds against a local JDK 21 toolchain, installs the plugin, copies
the benchmark maps, and boots the server:

```sh
./gradlew run
```

To run the production server in Docker instead &mdash; handy for a longer-lived or
remote instance &mdash; build the plugin jar and hand it to a Paper image:

```sh
./gradlew :plugin:shadowJar    # produces plugin/build/libs/pumpkin-agents-0.1.0-all.jar

docker run -d --name pumpkins -p 25565:25565 \
  -e EULA=TRUE -e TYPE=PAPER -e VERSION=1.19.4 \
  -v "$PWD/plugin/build/libs/pumpkin-agents-0.1.0-all.jar:/plugins/pumpkin-agents.jar" \
  -v "$PWD/maps:/data/plugins/PumpkinAgents/maps" \
  itzg/minecraft-server
```

Then drive it from the in-game console (or over RCON &mdash; under Docker that's
`docker exec -i pumpkins rcon-cli /pumpkin …`):

```
/pumpkin map load wumpus_8     # load a benchmark map
/pumpkin spawn PROLOG 1 1      # spawn a brain at (x, z)
/pumpkin run 200               # advance the scheduler 200 steps
/pumpkin state                 # what the agent sees and believes
/pumpkin metrics               # print + append a row to metrics.csv
```

To watch the agents live, point any Minecraft 1.19.4 client (for example,
[Prism Launcher](https://prismlauncher.org/)) at `localhost` server.

Other commands: `map save`, `step` (one tick), `reset` (clear agents, keep the
map). Brains accepted by `spawn`: `REFLEX BFS DFS UCS ASTAR ASTAR_EUCLID
ASTAR_BAD MINIMAX ALPHABETA PROLOG`.

## Test it

```sh
./gradlew :plugin:test    # fast Kotest unit tests for the brains
./gradlew check           # unit + e2e (boots a real Paper server, drives /pumpkin over RCON)
```

## Configure it

`plugins/PumpkinAgents/config.json`, written on first launch:

```json
{ "decisionsPerSecond": 2, "randomSeed": 1234, "defaultBrain": "ASTAR" }
```

Built on [plugin-api](https://gitlab.com/CMDR_Tvis/plugin-api) &mdash; a Kotlin/Bukkit
DSL the author wrote in 2019&ndash;2020, used here unmodified. See `REPORT.md` for the
write-up and `DEMO.md` for the video walkthrough.
