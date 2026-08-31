/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CmfWatchfaceFileTest {

    /** The opening bytes of a real Pro 2 face, followed by enough filler to be plausible. */
    private fun file(
        name: String,
        version: ByteArray = CmfWatchfaceFile.VERSION_WATCH_PRO_2,
        size: Int = 256,
        declared: Int = size - HEADER_BYTES,
    ): ByteArray {
        val bytes = ByteArray(size)
        // Four bytes that differ between files and are not a CRC of anything else in them.
        "d3879fb9".hexToBytes().copyInto(bytes, 0)
        version.copyInto(bytes, 4)
        name.toByteArray().copyInto(bytes, 8)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24, declared)
        // Filler that is not zero, so trimming is visible as something other than padding.
        for (i in HEADER_BYTES until size) bytes[i] = (i and 0x7f).toByte()
        return bytes
    }

    /** What a watchface site hands out: a device file with its own header stuck on the end. */
    private fun wrapped(inside: ByteArray, extra: Int = 0): ByteArray =
        inside + ByteArray(extra) { 0x5a } + inside.copyOf(HEADER_BYTES) + "ced23cc5".hexToBytes()

    private companion object {
        const val HEADER_BYTES = 36
    }

    @Test
    fun `a Watch Pro 2 face reads its name and its length`() {
        // Taken from a face pulled out of a capture: version at offset 4, name at 8.
        val reading = CmfWatchfaceFile.read(file("Combo", size = 58217))

        assertEquals(CmfWatchfaceFile.Reading.Ok("Combo", 58217), reading)
    }

    @Test
    fun `a face for the original CMF Watch is named as such rather than refused vaguely`() {
        // Worth telling apart from a corrupt file: somebody who downloaded the wrong
        // watch's face can act on that, and cannot act on "invalid".
        val reading = CmfWatchfaceFile.read(file("Combo", CmfWatchfaceFile.VERSION_WATCH_1))

        assertEquals(CmfWatchfaceFile.Reading.ForTheOtherWatch, reading)
    }

    @Test
    fun `anything else is refused with the version it actually carried`() {
        val reading = CmfWatchfaceFile.read(file("Combo", byteArrayOf(9, 9, 9, 9)))

        assertEquals(CmfWatchfaceFile.Reading.UnknownVersion(listOf(9, 9, 9, 9)), reading)
    }

    @Test
    fun `a file with no name where the name belongs is refused`() {
        val bytes = file("Combo")
        // Wipe the terminator and everything a name could hide behind.
        for (i in 8 until 8 + 64) bytes[i] = 1

        assertEquals(CmfWatchfaceFile.Reading.NoName, CmfWatchfaceFile.read(bytes))
    }

    @Test
    fun `something far too short to be a face is refused before anything is read from it`() {
        assertEquals(CmfWatchfaceFile.Reading.TooSmall, CmfWatchfaceFile.read(ByteArray(8)))
        assertEquals(CmfWatchfaceFile.Reading.TooSmall, CmfWatchfaceFile.read(ByteArray(0)))
    }

    @Test
    fun `the transfer request is the frame a real install sent`() {
        // Decrypted from a capture of the official app: mode 3, replacing 366, installing
        // 323, 76104 bytes. Little-endian, unlike the chunk requests that follow it.
        val request = CmfWatchfaceFile.transferRequest(replacedId = 366, newId = 323, size = 76104)

        assertEquals("036e0100004301000048290100", request.toHex())
    }

    @Test
    fun `a chunk request reads big-endian, the other way round from the request that began it`() {
        // The watch's first ask in that same capture: the whole file from zero, in 3072
        // byte pieces, nought percent done.
        val request = CmfWatchfaceFile.parseChunkRequest("0000000000000c0000".hexToBytes())

        assertEquals(CmfWatchfaceFile.ChunkRequest(0, 3072, 0), request)
    }

    @Test
    fun `a chunk request that cannot be acted on is refused rather than clamped`() {
        // An offset reCMF would have to invent a meaning for, and a length that would read
        // nothing. Sending a guess at either would put wrong bytes into a watch's storage.
        assertNull(CmfWatchfaceFile.parseChunkRequest("ffffffff00000c0000".hexToBytes()))
        assertNull(CmfWatchfaceFile.parseChunkRequest("000000000000000000".hexToBytes()))
        assertNull(CmfWatchfaceFile.parseChunkRequest("0000".hexToBytes()))
    }

    @Test
    fun `a file whose header accounts for every byte after it is sent as it is`() {
        // The one face known to have been accepted was 76104 bytes and said 76068, which
        // is the whole file bar its 36 byte header.
        val bytes = file("Dash", size = 76104, declared = 76068)

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertInstanceOf(CmfWatchfaceFile.Packaging.Device::class.java, packaging)
        assertArrayEquals(bytes, packaging.bytes)
    }

    @Test
    fun `a downloaded face is cut back to the device file inside its wrapper`() {
        // The refused one: 58217 bytes declaring 57137, with the first 36 bytes repeated
        // at the end. 57137 + 36 = 57173 is the file the watch is meant to receive.
        val inside = file("Combo", size = 57173, declared = 57137)
        val bytes = wrapped(inside, extra = 1004)

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertInstanceOf(CmfWatchfaceFile.Packaging.Trimmed::class.java, packaging)
        assertEquals(58217, bytes.size)
        assertArrayEquals(inside, packaging.bytes)
    }

    @Test
    fun `a length that fits neither shape is sent whole rather than trimmed on a guess`() {
        // Trimming rests on one accepted file and one refused one. A file that matches
        // neither is not evidence for the rule, and cutting it would destroy a face that
        // might have installed.
        val bytes = file("Odd", size = 4096, declared = 1234)

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertInstanceOf(CmfWatchfaceFile.Packaging.Unexplained::class.java, packaging)
        assertArrayEquals(bytes, packaging.bytes)
        assertEquals(1234, (packaging as CmfWatchfaceFile.Packaging.Unexplained).declared)
    }

    @Test
    fun `a wrapper is only recognised by the header it repeats`() {
        // Same lengths, different bytes at the end. Without the repeat there is nothing
        // saying where the file inside stops, so nothing is cut.
        val inside = file("Combo", size = 57173, declared = 57137)
        val bytes = inside + ByteArray(1044) { 0x11 }

        assertInstanceOf(
            CmfWatchfaceFile.Packaging.Unexplained::class.java,
            CmfWatchfaceFile.prepare(bytes),
        )
    }

    @Test
    fun `a header claiming more than the file holds is never trusted with a length`() {
        val bytes = file("Combo", size = 256, declared = 1 shl 30)

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertInstanceOf(CmfWatchfaceFile.Packaging.Unexplained::class.java, packaging)
        assertArrayEquals(bytes, packaging.bytes)
    }
}
