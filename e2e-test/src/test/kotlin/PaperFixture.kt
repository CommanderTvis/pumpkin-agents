package io.github.commandertvis.pumpkins.e2e

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.*

/**
 * Spins up a Paper server in a fresh `build/e2e-server/` directory and exposes
 * an RCON connection. One instance per test class is enough — start in @BeforeAll,
 * stop in @AfterAll.
 */
class PaperFixture private constructor(
    val serverDir: Path,
    val process: Process,
    val rconHost: String,
    val rconPort: Int,
    val rconPassword: String,
    private val readerThreads: List<Thread>
) : AutoCloseable {
    val rcon: RconClient by lazy {
        val client = RconClient(rconHost, rconPort, rconPassword)
        client.authenticate()
        client
    }

    val pluginsDir: Path
        get() = serverDir / "plugins"

    val pumpkinDataDir: Path
        get() = pluginsDir / "PumpkinAgents"

    val metricsDir: Path
        get() = pumpkinDataDir / "metrics"

    val latestLog: Path
        get() = serverDir / "logs" / "latest.log"

    fun sendCommand(cmd: String): String = rcon.execute(cmd).trim()

    override fun close() {
        try {
            runCatching { rcon.execute("stop") }
        } catch (_: Exception) {
        }
        try {
            rcon.close()
        } catch (_: Exception) {
        }

        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        for (t in readerThreads) {
            t.join(2000)
        }
    }

    companion object {
        /** Boot Paper, wait for "Done" log line, perform RCON auth. */
        fun boot(): PaperFixture {
            val props = sysProps()
            val serverDir = Path.of(props.getValue("pumpkin.serverDir")).also { it.createDirectories() }
            // Wipe stale state from previous runs.
            serverDir.toFile().listFiles()?.forEach { it.deleteRecursively() }
            serverDir.createDirectories()

            // Provision: eula, server.properties (with RCON), plugins/, maps/
            val rconPort = findFreePort()
            val gamePort = findFreePort()
            val queryPort = findFreePort()
            val password = "pumpkin_${System.currentTimeMillis()}"

            serverDir.resolve("eula.txt").writeText("eula=true\n")
            serverDir.resolve("server.properties").writeText(
                """
                online-mode=false
                server-port=$gamePort
                enable-rcon=true
                rcon.port=$rconPort
                rcon.password=$password
                broadcast-rcon-to-ops=false
                enable-query=false
                query.port=$queryPort
                level-type=FLAT
                spawn-protection=0
                max-players=4
                view-distance=4
                difficulty=peaceful
                gamemode=creative
                allow-nether=false
                generate-structures=false
                spawn-monsters=false
                spawn-animals=false
                """.trimIndent().trimStart() + "\n"
            )
            // Skip Paper's first-run notice & speed it up where we can
            serverDir.resolve("paper.yml").writeText("settings:\n  velocity-support:\n    enabled: false\n")

            val pluginsDir = serverDir.resolve("plugins").also { it.createDirectories() }
            val pluginJar = Path.of(props.getValue("pumpkin.pluginJar"))
            val runtimeJar = Path.of(props.getValue("pumpkin.pluginApiRuntimeJar"))
            pluginJar.copyTo(pluginsDir.resolve(pluginJar.fileName), overwrite = true)
            check(runtimeJar.exists()) { "plugin-api runtime jar missing: $runtimeJar" }
            runtimeJar.copyTo(pluginsDir.resolve(runtimeJar.fileName), overwrite = true)

            val mapsSrc = Path.of(props.getValue("pumpkin.mapsDir"))
            val mapsDst = pluginsDir.resolve("PumpkinAgents/maps").also { it.createDirectories() }
            mapsSrc.listDirectoryEntries("*.yml").forEach { it.copyTo(mapsDst.resolve(it.fileName), overwrite = true) }

            // Spawn Paper as a child process
            val java = ProcessHandle.current().info().command().orElse("java")
            val paperJar = Path.of(props.getValue("pumpkin.paperJar"))
            check(paperJar.exists()) { "Paper jar missing: $paperJar" }
            val command = listOf(
                java,
                "-Xms1G", "-Xmx1G",
                "-DPaper.IgnoreJavaVersion=true",
                "-Dcom.mojang.eula.agree=true",
                "-jar", paperJar.absolutePathString(),
                "--nogui"
            )
            val proc = ProcessBuilder(command)
                .directory(serverDir.toFile())
                .redirectErrorStream(false)
                .start()

            // Stream stdout/stderr into bounded queues for assertions later
            val stdoutQ = LinkedBlockingQueue<String>(8192)
            val stderrQ = LinkedBlockingQueue<String>(8192)
            val tOut = thread(start = true, isDaemon = true, name = "paper-stdout") {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    r.lineSequence().forEach {
                        println("[paper] $it")
                        if (stdoutQ.remainingCapacity() > 0) stdoutQ.offer(it)
                    }
                }
            }
            val tErr = thread(start = true, isDaemon = true, name = "paper-stderr") {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach {
                        System.err.println("[paper!] $it")

                        if (stderrQ.remainingCapacity() > 0)
                            stderrQ.offer(it)
                    }
                }
            }

            // Wait for the server to print Done (boot took ~10–20s on developer laptops)
            val readyDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
            var ready = false
            while (System.nanoTime() < readyDeadline) {
                val line = stdoutQ.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if ("Done (" in line && "For help" in line) {
                    ready = true; break
                }
                if ("RCON running" in line || "RCON listening" in line) {
                    // We can authenticate now even if "Done" hasn't printed.
                }
            }
            if (!ready) {
                proc.destroyForcibly()
                throw IOException("Paper did not become ready within 3 minutes")
            }
            return PaperFixture(serverDir, proc, "127.0.0.1", rconPort, password, listOf(tOut, tErr))
        }

        private fun sysProps(): Map<String, String> = listOf(
            "pumpkin.paperJar", "pumpkin.pluginApiRuntimeJar", "pumpkin.pluginJar",
            "pumpkin.mapsDir", "pumpkin.serverDir"
        ).associateWith { System.getProperty(it) ?: error("missing system property: $it") }

        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
