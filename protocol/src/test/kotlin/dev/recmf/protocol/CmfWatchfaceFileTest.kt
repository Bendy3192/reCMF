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
        declared: Int = size,
        resources: Int = declared - 120,
    ): ByteArray {
        val bytes = ByteArray(size)
        // Four bytes that differ between files and are not a CRC of anything else in them.
        "d3879fb9".hexToBytes().copyInto(bytes, 0)
        version.copyInto(bytes, 4)
        name.toByteArray().copyInto(bytes, 8)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(24, declared)
            .putInt(28, resources)
        // Filler that is not zero, so trimming is visible as something other than padding.
        for (i in HEADER_BYTES until size) bytes[i] = (i and 0x7f).toByte()
        return bytes
    }

    /** Closes a file the way a real one closes: its own first 36 bytes, again. */
    private fun closed(bytes: ByteArray): ByteArray = bytes + bytes.copyOf(HEADER_BYTES)

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

    /** Puts back the CRC32 a Bluetooth transfer's messages carry, as a bad rebuild leaves them. */
    private fun asRebuiltFromACapture(bytes: ByteArray, run: Int = 224): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < bytes.size) {
            val take = minOf(run, bytes.size - at)
            val piece = bytes.copyOfRange(at, at + take)
            val crc = java.util.zip.CRC32().apply { update(piece) }.value
            out.write(piece)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(crc.toInt()).array())
            at += take
        }
        return out.toByteArray()
    }

    @Test
    fun `a file that ends with its own header and says so is sent untouched`() {
        // The accepted face, to its real numbers: 76104 bytes, 76068 at offset 24, and
        // its own first 36 bytes again at exactly 76068.
        val bytes = closed(file("Dash", size = 76068, declared = 76068, resources = 74796))

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertEquals(76104, bytes.size)
        assertInstanceOf(CmfWatchfaceFile.Packaging.Device::class.java, packaging)
        assertArrayEquals(bytes, packaging.bytes)
    }

    @Test
    fun `a face rebuilt from a capture has its leftover checksums pulled back out`() {
        // What was going round as a download: the file the watch wants, with every
        // message's CRC32 still in it. 1044 bytes of them, spread through the file
        // rather than sitting on the end.
        val sound = closed(file("Combo", size = 57137, declared = 57137, resources = 56214))
        val bytes = asRebuiltFromACapture(sound)

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertEquals(57173, sound.size)
        assertInstanceOf(CmfWatchfaceFile.Packaging.Repaired::class.java, packaging)
        assertArrayEquals(sound, packaging.bytes)
    }

    @Test
    fun `messages of uneven length are still found`() {
        // The watch asks for the file in 3072-byte stretches, which do not divide by the
        // 224 a full message carries, so every stretch ends short and so does the file.
        val sound = closed(file("Combo", size = 20000, declared = 20000, resources = 19000))

        val packaging = CmfWatchfaceFile.prepare(asRebuiltFromACapture(sound, run = 160))

        assertInstanceOf(CmfWatchfaceFile.Packaging.Repaired::class.java, packaging)
        assertArrayEquals(sound, packaging.bytes)
    }

    @Test
    fun `a file that no repair lands on soundly is sent as it came`() {
        // The repair is only used when what comes out accounts for itself exactly. Random
        // length on the end is not a checksum, so nothing is found and nothing is cut.
        val bytes = closed(file("Odd", size = 4096, declared = 4096, resources = 3000)) +
            ByteArray(64) { 0x11 }

        val packaging = CmfWatchfaceFile.prepare(bytes)

        assertInstanceOf(CmfWatchfaceFile.Packaging.Unexplained::class.java, packaging)
        assertArrayEquals(bytes, packaging.bytes)
    }

    @Test
    fun `a file whose header does not account for it is not called sound`() {
        // Right closing copy, wrong length in front of it.
        val bytes = closed(file("Odd", size = 4096, declared = 3000, resources = 2000))

        assertInstanceOf(
            CmfWatchfaceFile.Packaging.Unexplained::class.java,
            CmfWatchfaceFile.prepare(bytes),
        )
    }
}
