/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmfLocationTest {

    @Test
    fun `a position is twelve big-endian bytes`() {
        // 51.5074N, 0.1278W at epoch 0x01020304, so every field is visible in the hex.
        val payload = CmfLocation.gpsCoords(51.5074, -0.1278, 0x0102_0304L)

        assertEquals(CmfLocation.PAYLOAD_SIZE, payload.size)
        assertEquals(
            // 515074000 = 0x1eb367d0, and -1278000 = 0xffec7fd0 as a signed 32-bit.
            "01020304" + "1eb367d0" + "ffec7fd0",
            payload.toHex(),
        )
    }

    @Test
    fun `south and west stay negative`() {
        // Read unsigned, a western longitude lands at 429 degrees east, which is nowhere,
        // and would seed the receiver with a hint worse than none.
        val payload = CmfLocation.gpsCoords(-33.8688, 151.2093, 0)
        val latitude = ((payload[4].toInt() and 0xff) shl 24) or
            ((payload[5].toInt() and 0xff) shl 16) or
            ((payload[6].toInt() and 0xff) shl 8) or
            (payload[7].toInt() and 0xff)

        assertTrue(latitude < 0, "a southern latitude must encode negative, was $latitude")
        assertEquals(-338_688_000, latitude)
    }

    @Test
    fun `degrees round rather than truncate`() {
        // A seventh decimal place is about a centimetre, so this changes nothing on the
        // ground — but truncation would bias every coordinate towards the equator, and a
        // rounding rule that is decided rather than inherited is one fewer thing to guess.
        val payload = CmfLocation.gpsCoords(0.00000019, 0.0, 0)

        assertEquals("00000000" + "00000002" + "00000000", payload.toHex())
    }

    @Test
    fun `a timestamp past 2038 still fits the field it is given`() {
        // Four bytes of epoch seconds run out in 2038. Pinned so that the wrap is
        // recognised as the field's limit rather than mistaken for a bug here.
        val payload = CmfLocation.gpsCoords(0.0, 0.0, 0xFFFF_FFFFL)

        assertEquals("ffffffff", payload.copyOf(4).toHex())
    }

    @Test
    fun `null island is not worth sending`() {
        // What a failed fix looks like, rather than a place anyone is.
        assertFalse(CmfLocation.worthSending(0.0, 0.0))
    }

    @Test
    fun `a real place is worth sending`() {
        assertTrue(CmfLocation.worthSending(51.5074, -0.1278))
        assertTrue(CmfLocation.worthSending(0.0, 12.5))
    }

    @Test
    fun `a position off the planet is not worth sending`() {
        assertFalse(CmfLocation.worthSending(91.0, 0.0))
        assertFalse(CmfLocation.worthSending(0.0, 181.0))
    }
}
