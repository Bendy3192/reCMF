/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
