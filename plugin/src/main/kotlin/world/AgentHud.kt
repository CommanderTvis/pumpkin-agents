package io.github.commandertvis.pumpkins.world

import me.filoghost.holographicdisplays.api.HolographicDisplaysAPI
import me.filoghost.holographicdisplays.api.hologram.Hologram
import org.bukkit.Location
import org.bukkit.plugin.Plugin

/**
 * Floating multi-line label above each agent, backed by HolographicDisplays.
 *
 * If the HolographicDisplays plugin is not installed at runtime, every call becomes a no-op —
 * the JVM only resolves HD's symbols once a `doXxx` method is actually invoked, which never
 * happens when `available` is `false`. This is why the field-typed cache map stores `Any`
 * instead of `Hologram`: it keeps the HD class out of `AgentHud`'s own bytecode signature.
 */
class AgentHud(private val plugin: Plugin) {
    private val available: Boolean =
        plugin.server.pluginManager.isPluginEnabled("HolographicDisplays")

    private val holograms: HashMap<Int, Any> = HashMap()

    fun update(snapshot: HudSnapshot) {
        if (!available) return
        doUpdate(snapshot)
    }

    fun remove(id: Int) {
        if (!available) return
        doRemove(id)
    }

    fun clear() {
        if (!available) return
        doClear()
    }

    // --- methods below touch HolographicDisplays types; only invoked when the plugin is loaded ---

    private fun doUpdate(s: HudSnapshot) {
        val world = plugin.server.worlds.firstOrNull() ?: return
        val loc = Location(world, s.x + 0.5, s.y, s.z + 0.5)
        val existing = holograms[s.id] as Hologram?
        val holo: Hologram = if (existing == null || existing.isDeleted) {
            val fresh = HolographicDisplaysAPI.get(plugin).createHologram(loc)
            holograms[s.id] = fresh
            fresh
        } else {
            existing.setPosition(loc)
            existing
        }
        val lines = holo.lines
        lines.clear()
        for (line in s.lines) lines.appendText(line)
    }

    private fun doRemove(id: Int) {
        val h = holograms.remove(id) as Hologram? ?: return
        if (!h.isDeleted) h.delete()
    }

    private fun doClear() {
        for (h in holograms.values) {
            val holo = h as Hologram
            if (!holo.isDeleted) holo.delete()
        }
        holograms.clear()
    }
}

/** Position + lines payload for a single agent's hologram. */
data class HudSnapshot(
    val id: Int,
    val x: Int,
    val y: Double,
    val z: Int,
    val lines: List<String>
)
