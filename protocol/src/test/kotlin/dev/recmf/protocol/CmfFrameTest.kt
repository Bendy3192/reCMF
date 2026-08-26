package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CmfFrameTest {
    @Test
    fun `header round-trips through the parser`() {
        val header = CmfFrame.header(CmfCommand.ACTIVITY_DATA, bodyLength = 32, chunkCount = 3, chunkIndex = 2)
        val body = ByteArray(32) { it.toByte() }

        val parsed = requireNotNull(CmfFrame.parse(header + body))

        assertEquals(32, parsed.bodyLength)
        assertEquals(3, parsed.chunkCount)
        assertEquals(2, parsed.chunkIndex)
        assertEquals(CmfCommand.ACTIVITY_DATA, parsed.command)
        assertArrayEquals(body, parsed.body)
    }

    @Test
    fun `the header is eleven bytes and starts with f5`() {
        val header = CmfFrame.header(CmfCommand.BATTERY, 0, 1, 1)

        assertEquals(CmfFrame.HEADER_SIZE, header.size)
        assertEquals(0xf5.toByte(), header[0])
    }

    @Test
    fun `opcodes above 0x7fff survive the signed short round trip`() {
        // cmd2 = 0xa057 does not fit a signed short; a naive read would come back negative.
        val header = CmfFrame.header(CmfCommand.ACTIVITY_FETCH_ACK_2, 0, 1, 1)
        val parsed = requireNotNull(CmfFrame.parse(header))

        assertEquals(0xffff, parsed.cmd1)
        assertEquals(0xa057, parsed.cmd2)
        assertEquals(CmfCommand.ACTIVITY_FETCH_ACK_2, parsed.command)
    }

    @Test
    fun `short values and a wrong magic byte parse to null`() {
        assertNull(CmfFrame.parse(ByteArray(0)))
        assertNull(CmfFrame.parse(ByteArray(CmfFrame.HEADER_SIZE - 1) { 0xf5.toByte() }))
        assertNull(CmfFrame.parse(ByteArray(CmfFrame.HEADER_SIZE)))
    }

    @Test
    fun `crc32 is emitted little-endian`() {
        // CRC32 of "123456789" is the standard 0xcbf43926 check value.
        assertArrayEquals(
            byteArrayOf(0x26, 0x39, 0xf4.toByte(), 0xcb.toByte()),
            CmfFrame.crc32le("123456789".toByteArray()),
        )
    }

    @Test
    fun `uint32 round-trips through both ends of the range`() {
        for (value in intArrayOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x0d0c0b0a)) {
            assertEquals(value, CmfFrame.readUint32le(CmfFrame.uint32le(value), 0))
        }
    }

    @Test
    fun `parsed frames compare by content`() {
        val a = requireNotNull(CmfFrame.parse(CmfFrame.header(CmfCommand.BATTERY, 2, 1, 1) + byteArrayOf(1, 2)))
        val b = requireNotNull(CmfFrame.parse(CmfFrame.header(CmfCommand.BATTERY, 2, 1, 1) + byteArrayOf(1, 2)))
        val c = requireNotNull(CmfFrame.parse(CmfFrame.header(CmfCommand.BATTERY, 2, 1, 1) + byteArrayOf(1, 3)))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(false, a == c)
    }
}

class CmfCommandTest {
    @Test
    fun `no two commands share an opcode pair`() {
        val seen = mutableMapOf<Pair<Int, Int>, CmfCommand>()
        for (cmd in CmfCommand.entries) {
            val clash = seen.put(cmd.cmd1 to cmd.cmd2, cmd)
            assertNull(clash, "$cmd collides with $clash")
        }
    }

    @Test
    fun `every command is reachable by its codes`() {
        for (cmd in CmfCommand.entries) {
            assertEquals(cmd, CmfCommand.fromCodes(cmd.cmd1, cmd.cmd2))
        }
    }

    @Test
    fun `unknown codes resolve to null`() {
        assertNull(CmfCommand.fromCodes(0x1234, 0x5678))
    }
}

class HexTest {
    @Test
    fun `hex round-trips`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte())

        assertEquals("000f7f80ff", bytes.toHex())
        assertArrayEquals(bytes, "000f7f80ff".hexToBytes())
    }

    @Test
    fun `a pasted key with 0x, capitals and spaces still parses`() {
        val expected = "000102030405060708090a0b0c0d0e0f".hexToBytes()

        assertArrayEquals(expected, "0x000102030405060708090A0B0C0D0E0F".hexToBytes())
        assertArrayEquals(expected, "00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f".hexToBytes())
    }

    @Test
    fun `malformed hex is rejected loudly`() {
        assertThrows(IllegalArgumentException::class.java) { "abc".hexToBytes() }
        assertThrows(IllegalArgumentException::class.java) { "zz".hexToBytes() }
    }
}
