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
    fun `a position is sixteen big-endian bytes, longitude before latitude`() {
        // 51.5074N, 0.1278W at epoch 0x01020304, so every field is visible in the hex.
        val payload = CmfLocation.gpsCoords(51.5074, -0.1278, 0x0102_0304L)

        assertEquals(CmfLocation.PAYLOAD_SIZE, payload.size)
        assertEquals(
            // -1278000 = 0xffec7fd0 as a signed 32-bit, and 515074000 = 0x1eb367d0.
            "01020304" + "ffec7fd0" + "1eb367d0" + "6f8531fd",
            payload.toHex(),
        )
    }

    @Test
    fun `the order is the one the official app sends, not the one that reads naturally`() {
        // Decrypted out of two captures of the official app a day apart: the second field
        // was the writer's longitude and the third their latitude, and the trailing four
        // bytes were the same in both. Written the other way round — as this was, for
        // months — a position in Moscow goes out as one in Uzbekistan, and the watch
        // hunts for satellites two thousand kilometres from the ones overhead.
        //
        // Pinned with a place whose two halves cannot be confused: no latitude is 100.
        val payload = CmfLocation.gpsCoords(latitude = 10.0, longitude = 100.0, epochSeconds = 0)

        assertEquals("00000000" + "3b9aca00" + "05f5e100" + "6f8531fd", payload.toHex())
    }

    @Test
    fun `south and west stay negative`() {
        // Read unsigned, a western longitude lands at 429 degrees east, which is nowhere,
        // and would seed the receiver with a hint worse than none.
        val payload = CmfLocation.gpsCoords(-33.8688, 151.2093, 0)
        val latitude = ((payload[8].toInt() and 0xff) shl 24) or
            ((payload[9].toInt() and 0xff) shl 16) or
            ((payload[10].toInt() and 0xff) shl 8) or
            (payload[11].toInt() and 0xff)

        assertTrue(latitude < 0, "a southern latitude must encode negative, was $latitude")
        assertEquals(-338_688_000, latitude)
    }

    @Test
    fun `degrees round rather than truncate`() {
        // A seventh decimal place is about a centimetre, so this changes nothing on the
        // ground — but truncation would bias every coordinate towards the equator, and a
        // rounding rule that is decided rather than inherited is one fewer thing to guess.
        val payload = CmfLocation.gpsCoords(0.00000019, 0.0, 0)

        assertEquals("00000000" + "00000000" + "00000002" + "6f8531fd", payload.toHex())
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
