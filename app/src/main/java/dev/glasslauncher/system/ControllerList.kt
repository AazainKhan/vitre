package dev.glasslauncher.system

import android.content.Context
import android.view.InputDevice

/** One game controller in the Control Center's Game Controllers platter. */
data class ControllerRow(val id: Int, val name: String, val line: String)

object ControllerList {
    /** A real gamepad or joystick: not a virtual device and not the Fire remote (which is a keyboard and d-pad). */
    fun isController(sources: Int, virtual: Boolean, name: String?): Boolean =
        !virtual && !RemotePairPolicy.isAmazonRemoteName(name) &&
            ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)

    /** Suffix of a pad re-emitted by a bridge (glass-ds5-bridge): the raw pad it replaces is hidden from the list (P319). */
    const val BRIDGE_SUFFIX = " (Glass)"

    /** For each of [names]: false for a raw pad whose bridged twin ("<name> (Glass)") is also connected. */
    fun withoutBridged(names: List<String?>): List<Boolean> = names.map { n -> n == null || (n + BRIDGE_SUFFIX) !in names }

    /** "Connected", or "Connected · Battery 80%" when the Bluetooth stack reports a level for the bonded device of the same name. */
    fun line(batteryLevel: Int): String = RemoteStatus.battery(batteryLevel)?.let { "Connected · Battery $it" } ?: "Connected"

    @android.annotation.SuppressLint("MissingPermission")
    fun read(context: Context): List<ControllerRow> {
        val bonded = runCatching {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().associateBy { runCatching { it.name }.getOrNull() }
        }.getOrDefault(emptyMap())
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { isController(it.sources, it.isVirtual, it.name) }
        return pads.zip(withoutBridged(pads.map { it.name })).filter { it.second }.map { it.first }
            .map { d ->
                val battery = bonded[d.name.removeSuffix(BRIDGE_SUFFIX)]?.let { b -> runCatching { android.bluetooth.BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(b) as Int }.getOrDefault(-1) } ?: -1
                ControllerRow(d.id, d.name, line(battery))
            }
    }
}
