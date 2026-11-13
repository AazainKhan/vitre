package dev.glasslauncher

import dev.glasslauncher.system.ControllerList
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerListTest {
    @Test fun theRawPadIsHiddenWhenItsBridgedTwinIsConnected() {
        val names = listOf("DualSense Wireless Controller", "DualSense Wireless Controller (Glass)", "Xbox Wireless Controller")
        assertEquals(listOf(false, true, true), ControllerList.withoutBridged(names))
    }
    @Test fun theRawPadStaysWithoutTheBridge() {
        assertEquals(listOf(true), ControllerList.withoutBridged(listOf("DualSense Wireless Controller")))
    }
}
