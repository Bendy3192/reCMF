package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bytes here are from a real Watch Pro 2 (firmware 1.0.0.73), captured by sending the
 * `0x0002` half of each pair and reading what came back under the `0x0001` half.
 */
class CmfSettingsReadBackTest {

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `raise to wake reads back as on`() {
        assertTrue(CmfSettings.parseWakeOnWristRaise(bytes("01")) == true)
        assertFalse(CmfSettings.parseWakeOnWristRaise(bytes("00")) == true)
        assertNull(CmfSettings.parseWakeOnWristRaise(ByteArray(0)))
    }

    @Test
    fun `the clock format is inverted, as it is on the way out`() {
        // Zero is the 24-hour clock, which is what the captured watch was set to.
        assertTrue(CmfSettings.parseTimeFormat(bytes("00")) == true)
        assertFalse(CmfSettings.parseTimeFormat(bytes("01")) == true)
    }

    @Test
    fun `a format read back survives a round trip through the writer`() {
        for (use24Hour in listOf(true, false)) {
            assertEquals(use24Hour, CmfSettings.parseTimeFormat(CmfSettings.timeFormat(use24Hour)))
        }
    }

    @Test
    fun `do not disturb reads back as off`() {
        assertFalse(CmfSettings.parseDoNotDisturb(bytes("00")) == true)
    }

    @Test
    fun `the sport list is a count and then codes`() {
        assertEquals(
            listOf(CmfActivityType.OUTDOOR_RUNNING, CmfActivityType.INDOOR_RUNNING),
            CmfSettings.parseSportTypes(bytes("020203")),
        )
    }

    @Test
    fun `a sport list survives a round trip through the writer`() {
        val chosen = listOf(
            CmfActivityType.OUTDOOR_WALKING,
            CmfActivityType.YOGA,
            CmfActivityType.INDOOR_CYCLING,
        )

        assertEquals(chosen, CmfSettings.parseSportTypes(CmfSettings.sportTypes(chosen)))
    }

    @Test
    fun `a sport code this build does not know is dropped, not fatal`() {
        // The watch's menu is longer than the table here, so an unknown code must not
        // throw away the sports that were recognised.
        assertEquals(
            listOf(CmfActivityType.OUTDOOR_RUNNING),
            CmfSettings.parseSportTypes(bytes("0202fe")),
        )
    }

    @Test
    fun `a truncated sport list is refused rather than half-read`() {
        assertNull(CmfSettings.parseSportTypes(bytes("0502")))
        assertNull(CmfSettings.parseSportTypes(ByteArray(0)))
    }

    @Test
    fun `the goals are read as the watch's own screen showed them`() {
        // Steps, calories, active minutes and the climb byte were all read off the watch
        // and match. Distance is the one number nobody had set, so 4000 agreeing with
        // itself is not the same kind of evidence.
        val goals = CmfSettings.parseGoals(
            bytes("10270000a00f000090010000d00200001e0000000c01010101010101"),
        )

        assertEquals(10_000, goals?.steps)
        assertEquals(400, goals?.calories)
        assertEquals(30, goals?.activeMinutes)
        assertEquals(12, goals?.climbs)
        assertEquals(4_000, goals?.distanceMeters)
        assertEquals(720, goals?.unidentified)
    }

    @Test
    fun `a goal reply too short for the climb byte is refused`() {
        // One byte short of the whole thing: five numbers present, the climb byte not.
        assertNull(CmfSettings.parseGoals(bytes("10270000a00f000090010000d00200001e000000")))
        assertNull(CmfSettings.parseGoals(bytes("10270000a00f0000")))
    }
}
