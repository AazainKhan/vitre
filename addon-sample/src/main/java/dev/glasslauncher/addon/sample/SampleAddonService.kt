package dev.glasslauncher.addon.sample

import dev.glasslauncher.addon.ActionInfo
import dev.glasslauncher.addon.GlassAddonService
import dev.glasslauncher.addon.TileState

/** Reference add-on: a counter that grows on Select, and a read-only value tile. */
class SampleAddonService : GlassAddonService() {
    // Glass unbinds after every call, so the process can die between taps: the count lives in storage.
    private val prefs get() = getSharedPreferences("sample", MODE_PRIVATE)

    private fun counter() = prefs.getInt("count", 0).let { TileState("counter", "Counter", "add", on = it > 0, value = it.toString()) }
    private fun apiTile() = TileState("api", "API Level", "settings", value = "VITRE API ${dev.glasslauncher.addon.AddonApi.LEVEL}")

    override fun tiles() = listOf(counter(), apiTile())

    override fun onTap(id: String): TileState? {
        if (id == "counter") prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).apply()
        return tile(id)
    }

    override fun actions() = listOf(ActionInfo("increment", "Increment counter"))

    override fun onAction(id: String): String? {
        if (id != "increment") return null
        val n = prefs.getInt("count", 0) + 1
        prefs.edit().putInt("count", n).apply()
        return "Counter: $n"
    }
}
