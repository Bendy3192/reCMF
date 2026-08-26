package dev.recmf.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchScannerTest {
    private fun watch(name: String?, bonded: Boolean = false, rssi: Int? = null) =
        DiscoveredWatch(address = "00:11:22:33:44:${name?.length ?: 0}", name = name, rssi = rssi, isBonded = bonded)

    @Test
    fun `a real CMF Watch Pro 2 name is recognised`() {
        // The name a Watch Pro 2 actually reports, suffix and all.
        assertTrue(watch("CMF Watch Pro 2-7219").looksLikeWatch)
        assertTrue(watch("CMF Watch Pro 2").looksLikeWatch)
        assertTrue(watch("Watch Pro").looksLikeWatch)
    }

    @Test
    fun `other devices in range are not mistaken for a watch`() {
        assertFalse(watch("SLBLE").looksLikeWatch)
        assertFalse(watch("OBDII").looksLikeWatch)
        assertFalse(watch(null).looksLikeWatch)
    }

    @Test
    fun `a device with no name is still offered`() {
        // The watch can advertise without a name, so nothing may be filtered out —
        // only sorted. This is what a UUID filter got wrong.
        val unnamed = watch(null)

        assertFalse(unnamed.looksLikeWatch)
        assertEquals("00:11:22:33:44:0", unnamed.address)
    }

    @Test
    fun `a bonded device is distinguishable from a scanned one`() {
        assertTrue(watch("CMF Watch Pro 2-7219", bonded = true).isBonded)
        assertEquals(null, watch("CMF Watch Pro 2-7219", bonded = true).rssi)
        assertEquals(-40, watch("SLBLE", rssi = -40).rssi)
    }
}
