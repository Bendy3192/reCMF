/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmfAgpsTest {

    @Test
    fun `the request is the one the official app sent, byte for byte`() {
        // Decrypted out of a capture of the official app uploading a real EPO file: 97872
        // bytes whose CRC32 is 0x43f77398. This is the whole point of the class — the
        // opening command nobody had, including Gadgetbridge, which names the opcode and
        // never sends it. Pinned against what the watch actually accepted, so that a
        // tidier-looking layout cannot quietly replace it.
        val request = CmfAgps.transferRequest(size = 97_872, crc32 = 0x43f77398L)

        assertEquals(CmfAgps.REQUEST_SIZE, request.size)
        assertEquals("01" + "00017e50" + "9873f743" + "ff".repeat(31), request.toHex())
    }

    @Test
    fun `the size is big-endian and the checksum is little-endian`() {
        // Said twice, here and in the source, because it looks like a mistake: the two
        // fields of one payload disagree about byte order, and a reader who "fixes" one of
        // them gets a transfer the watch refuses.
        val request = CmfAgps.transferRequest(size = 0x01020304, crc32 = 0x0a0b0c0dL)

        assertEquals("01020304", request.copyOfRange(1, 5).toHex())
        assertEquals("0d0c0b0a", request.copyOfRange(5, 9).toHex())
    }

    @Test
    fun `a file's checksum is its plain CRC32`() {
        assertEquals(0x8bb98613L, CmfAgps.checksum(byteArrayOf(0, 1, 2, 3)))
    }

    /**
     * A file built to the record layout.
     *
     * The tag and the length are eight **ASCII** hex digits each, not four bytes each —
     * which is what makes the first twelve bytes spell `000000010000` and is easy to get
     * backwards when writing a fixture.
     */
    private fun epo(vararg lengths: Int): ByteArray {
        var out = ByteArray(0)
        lengths.forEachIndexed { index, length ->
            out += "%08x%08x".format(index + 1, length).toByteArray(Charsets.US_ASCII)
            out += ByteArray(length)
        }
        return out
    }

    @Test
    fun `a file whose records tile it exactly is an EPO file`() {
        // The real one was four records — 48384, 25128 and 24264 bytes of orbit data and
        // 32 of checksum — adding up to 97872 with nothing left over.
        assertTrue(CmfAgps.looksLikeEpo(epo(0x100, 0x80, 0x20)))
    }

    @Test
    fun `the magic alone is not enough`() {
        // What a download cut short looks like: it starts right and stops mid-record.
        // Sending it would waste a transfer and leave the watch with a broken almanac.
        val truncated = epo(0x100).copyOf(0x40)

        assertTrue(truncated.copyOf(12).contentEquals(CmfAgps.MAGIC))
        assertFalse(CmfAgps.looksLikeEpo(truncated))
    }

    @Test
    fun `trailing rubbish after the last record is refused`() {
        assertFalse(CmfAgps.looksLikeEpo(epo(0x20) + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `something that is not an EPO file at all is refused`() {
        assertFalse(CmfAgps.looksLikeEpo(ByteArray(0)))
        assertFalse(CmfAgps.looksLikeEpo("hello".toByteArray()))
        // Right magic, then a length field that is not hex.
        assertFalse(CmfAgps.looksLikeEpo("000000010000zzzz".toByteArray()))
    }

    /**
     * MediaTek's own layout: six-hour slots of [satellites] records of 72 bytes, the hour
     * in the first three of every record.
     *
     * Thirty-two is `EPO.DAT`, a month of GPS. Fifty-six is `EPO_GR_3_N.DAT`, three days
     * of GPS and GLONASS together, and is what the official app sends.
     */
    private fun epoDat(startHour: Int, slots: Int, satellites: Int = 32): ByteArray {
        val slotSize = satellites * 72
        val out = ByteArray(slots * slotSize)
        for (s in 0 until slots) {
            val hour = startHour + s * 6
            for (sat in 0 until satellites) {
                val at = s * slotSize + sat * 72
                out[at] = (hour and 0xff).toByte()
                out[at + 1] = ((hour shr 8) and 0xff).toByte()
                out[at + 2] = ((hour shr 16) and 0xff).toByte()
                out[at + 3] = (sat + 1).toByte()
            }
        }
        return out
    }

    @Test
    fun `an almanac is built from MediaTek's own orbits`() {
        // The whole point: the watch's receiver is a MediaTek part, MediaTek publishes the
        // orbits openly a month at a time, and the official app republishes a slice. This
        // cuts the same slice, so nothing has to be captured from the official app again.
        val built = CmfAgps.buildFromEpo(epoDat(408_960, 120), nowHourSinceGpsEpoch = 408_973)!!

        // Twelve six-hour slots is three days, wrapped as record 1 plus the trailer.
        val body = 12 * 2304
        assertEquals(16 + body + 16 + 32, built.size)
        assertTrue(CmfAgps.looksLikeEpo(built))
        assertEquals("00000001" + "%08x".format(body), built.copyOf(16).toHex().hexAsAscii())
    }

    @Test
    fun `it starts at the six-hour boundary at or before now, not after it`() {
        // Starting at the next boundary would leave the receiver without orbits for the
        // hours it is in right now, which is exactly when it is looking for satellites.
        val built = CmfAgps.buildFromEpo(epoDat(408_960, 120), nowHourSinceGpsEpoch = 408_977)!!
        val firstSlotHour = (built[16].toInt() and 0xff) or
            ((built[17].toInt() and 0xff) shl 8) or
            ((built[18].toInt() and 0xff) shl 16)

        assertEquals(408_972, firstSlotHour)
    }

    @Test
    fun `a file that does not reach three days ahead is refused rather than truncated`() {
        // A short file would leave the receiver trusting orbits for hours it never
        // covered, which is worse than having none at all.
        assertNull(CmfAgps.buildFromEpo(epoDat(408_960, 8), nowHourSinceGpsEpoch = 408_960))
        // And one that has run out entirely, as a month-old download would have.
        assertNull(CmfAgps.buildFromEpo(epoDat(408_960, 120), nowHourSinceGpsEpoch = 500_000))
    }

    @Test
    fun `something that is not MediaTek's file is refused`() {
        assertNull(CmfAgps.buildFromEpo(ByteArray(0), 408_960))
        assertNull(CmfAgps.buildFromEpo(ByteArray(2305), 408_960))
    }

    @Test
    fun `the GPS epoch is where the blocks count from`() {
        // 2026-09-01 12:00 UTC was hour 408972 in the file the official app sent, which is
        // what anchors this: get it wrong and the almanac is for the wrong day.
        assertEquals(408_972, CmfAgps.hoursSinceGpsEpoch(1_788_264_000L))
    }

    @Test
    fun `the watch's ask is read the same way a watchface's is`() {
        // 00000c00 offset, 00000c00 length, 03 percent — the second ask of the real
        // transfer, which walked the file in 3072-byte stretches.
        val ask = CmfAgps.parseChunkRequest("00000c0000000c0003".hexToBytes())!!

        assertEquals(3072, ask.offset)
        assertEquals(3072, ask.length)
        assertEquals(3, ask.percent)
    }

    @Test
    fun `the last ask lands exactly on the end of the file`() {
        // 95232 + 2640 = 97872, which is how the transfer was seen to finish.
        val ask = CmfAgps.parseChunkRequest("0001740000000a5061".hexToBytes())!!

        assertEquals(95_232, ask.offset)
        assertEquals(2_640, ask.length)
        assertEquals(97_872, ask.offset + ask.length)
    }

    /** A hex dump of ASCII digits, back to the digits themselves. */
    private fun String.hexAsAscii(): String =
        chunked(2).map { it.toInt(16).toChar() }.joinToString("")

    @Test
    fun `a file carrying GLONASS as well as GPS is recognised by its slots, not its size`() {
        // The one that matters where GPS is jammed. Three days of 56 satellites is 48384
        // bytes, which divides evenly by both 4032 and 2304 — so the shape has to be read
        // from where the hour changes, and a file this size must not be mistaken for
        // twenty-one slots of GPS.
        // Twelve slots from the boundary this hour falls in, which is the whole of what a
        // three-day file carries and exactly what is wanted.
        val built = CmfAgps.buildFromEpo(
            epoDat(408_972, slots = 12, satellites = 56),
            nowHourSinceGpsEpoch = 408_973,
        )!!

        val body = 12 * 56 * 72
        assertEquals(48_384, body)
        assertEquals(16 + body + 16 + 32, built.size)
        assertTrue(CmfAgps.looksLikeEpo(built))
        // The 33rd record of the first slot is a GLONASS satellite, and it survived.
        assertEquals(33, built[16 + 32 * 72 + 3].toInt())
    }

    @Test
    fun `a file with only one slot has no boundary to read and is refused`() {
        // Six hours of the three days wanted, so it would be refused anyway — but refused
        // for a stated reason rather than by reading the slot size off the file length.
        assertNull(CmfAgps.buildFromEpo(epoDat(408_960, slots = 1), 408_960))
    }
}
