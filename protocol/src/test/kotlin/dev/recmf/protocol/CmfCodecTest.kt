package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CmfCodecTest {
    private val key = "000102030405060708090a0b0c0d0e0f".hexToBytes()

    private fun codecPair(mtu: Int = CmfCodec.DEFAULT_MTU): Pair<CmfCodec, CmfCodec> {
        val tx = CmfCodec().apply { sessionKey = key; this.mtu = mtu }
        val rx = CmfCodec().apply { sessionKey = key; this.mtu = mtu }
        return tx to rx
    }

    /** Feeds every frame through [rx] and returns the payload the last one completed. */
    private fun roundTrip(tx: CmfCodec, rx: CmfCodec, cmd: CmfCommand, payload: ByteArray): ByteArray {
        val frames = tx.encode(cmd, payload)
        var result: ByteArray? = null

        frames.forEachIndexed { index, frame ->
            when (val decoded = rx.decode(frame)) {
                is CmfDecoded.Command -> {
                    assertEquals(frames.lastIndex, index, "payload completed before the last frame")
                    assertEquals(cmd, decoded.cmd)
                    result = decoded.payload
                }

                is CmfDecoded.Pending -> assertNotEquals(frames.lastIndex, index, "last frame left the payload pending")
                is CmfDecoded.Dropped -> error("frame $index dropped: ${decoded.reason}")
            }
        }

        return requireNotNull(result) { "no payload was completed" }
    }

    @Test
    fun `encrypted payload survives a round trip`() {
        val (tx, rx) = codecPair()
        val payload = Random(1).nextBytes(24)

        assertArrayEquals(payload, roundTrip(tx, rx, CmfCommand.APP_NOTIFICATION, payload))
    }

    @Test
    fun `payload larger than the MTU is split and reassembled`() {
        val (tx, rx) = codecPair()
        val payload = Random(2).nextBytes(4000)

        val frames = tx.encode(CmfCommand.ACTIVITY_DATA, payload)
        assertTrue(frames.size > 1, "expected multiple chunks, got ${frames.size}")
        frames.forEach { assertTrue(it.size <= CmfCodec.DEFAULT_MTU, "frame of ${it.size} exceeds the MTU") }

        assertArrayEquals(payload, roundTrip(tx, rx, CmfCommand.ACTIVITY_DATA, payload))
    }

    @Test
    fun `chunk headers count from one and agree on the total`() {
        val (tx, _) = codecPair()
        val frames = tx.encode(CmfCommand.ACTIVITY_DATA, Random(3).nextBytes(4000))

        frames.forEachIndexed { index, frame ->
            val parsed = requireNotNull(CmfFrame.parse(frame))
            assertEquals(frames.size, parsed.chunkCount)
            assertEquals(index + 1, parsed.chunkIndex)
        }
    }

    @Test
    fun `unencrypted commands are readable without a session key`() {
        val tx = CmfCodec()
        val rx = CmfCodec()
        val payload = Random(4).nextBytes(48)

        val frames = tx.encode(CmfCommand.AUTH_PAIR_REQUEST, payload)
        assertEquals(1, frames.size)

        val decoded = assertInstanceOf(CmfDecoded.Command::class.java, rx.decode(frames.single()))
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `a plaintext body without a trailing CRC is passed through untouched`() {
        // The watch's pairing reply arrives bare, unlike the frames we send.
        val rx = CmfCodec()
        val body = ByteArray(48) { it.toByte() }
        val frame = CmfFrame.header(CmfCommand.AUTH_PAIR_REPLY, body.size, 1, 1) + body

        val decoded = assertInstanceOf(CmfDecoded.Command::class.java, rx.decode(frame))

        assertArrayEquals(body, decoded.payload)
    }

    @Test
    fun `empty payload produces one frame with no body`() {
        val (tx, rx) = codecPair()

        val frames = tx.encode(CmfCommand.FIRMWARE_VERSION_GET)
        assertEquals(1, frames.size)
        assertEquals(CmfFrame.HEADER_SIZE, frames.single().size)

        val decoded = assertInstanceOf(CmfDecoded.Command::class.java, rx.decode(frames.single()))
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `a corrupted ciphertext byte is caught, not surfaced as a command`() {
        val (tx, rx) = codecPair()
        val frame = tx.encode(CmfCommand.APP_NOTIFICATION, Random(5).nextBytes(16)).single()

        // Flip a bit in the last block so padding still validates but the CRC will not.
        frame[frame.size - 17] = (frame[frame.size - 17].toInt() xor 0x01).toByte()

        val decoded = assertInstanceOf(CmfDecoded.Dropped::class.java, rx.decode(frame))
        assertTrue(
            decoded.reason in
                setOf(CmfDropReason.CRC_MISMATCH, CmfDropReason.DECRYPT_FAILED),
            "unexpected reason ${decoded.reason}",
        )
    }

    @Test
    fun `a frame encrypted under a different key is dropped`() {
        val (tx, _) = codecPair()
        val rx = CmfCodec().apply { sessionKey = "0f0e0d0c0b0a09080706050403020100".hexToBytes() }

        val frame = tx.encode(CmfCommand.APP_NOTIFICATION, Random(6).nextBytes(16)).single()

        assertInstanceOf(CmfDecoded.Dropped::class.java, rx.decode(frame))
    }

    @Test
    fun `a missing middle chunk aborts the payload instead of splicing`() {
        val (tx, rx) = codecPair()
        val frames = tx.encode(CmfCommand.ACTIVITY_DATA, Random(7).nextBytes(4000))
        check(frames.size >= 3)

        assertInstanceOf(CmfDecoded.Pending::class.java, rx.decode(frames[0]))

        val decoded = assertInstanceOf(CmfDecoded.Dropped::class.java, rx.decode(frames[2]))
        assertEquals(CmfDropReason.CHUNK_OUT_OF_ORDER, decoded.reason)
    }

    @Test
    fun `a restarted transfer discards the abandoned chunks`() {
        val (tx, rx) = codecPair()
        val payload = Random(8).nextBytes(4000)
        val frames = tx.encode(CmfCommand.ACTIVITY_DATA, payload)

        // Half of an earlier attempt, then the whole transfer again from chunk 1.
        rx.decode(frames[0])
        rx.decode(frames[1])

        assertArrayEquals(payload, roundTrip(tx, rx, CmfCommand.ACTIVITY_DATA, payload))
    }

    @Test
    fun `reassembly stops at the configured ceiling`() {
        val payload = Random(9).nextBytes(4000)
        val tx = CmfCodec().apply { sessionKey = key }
        val rx = CmfCodec(maxReassemblySize = 512).apply { sessionKey = key }

        val frames = tx.encode(CmfCommand.ACTIVITY_DATA, payload)
        val reasons = frames.map { rx.decode(it) }.filterIsInstance<CmfDecoded.Dropped>().map { it.reason }

        assertTrue(
            CmfDropReason.REASSEMBLY_TOO_LARGE in reasons,
            "expected the oversized payload to be refused, got $reasons",
        )
    }

    @Test
    fun `abandoned transfers cannot each claim a buffer`() {
        val (tx, rx) = codecPair()

        // Start a multi-chunk transfer for more commands than the codec will track,
        // never finishing any of them.
        val commands = listOf(
            CmfCommand.ACTIVITY_DATA,
            CmfCommand.SLEEP_DATA,
            CmfCommand.SPO2,
            CmfCommand.STRESS,
            CmfCommand.WORKOUT_SUMMARY,
        )
        check(commands.size > CmfCodec.MAX_CONCURRENT_REASSEMBLIES)

        val outcomes = commands.map { cmd ->
            rx.decode(tx.encode(cmd, Random(12).nextBytes(4000)).first())
        }

        assertTrue(
            outcomes.takeLast(commands.size - CmfCodec.MAX_CONCURRENT_REASSEMBLIES)
                .all { it is CmfDecoded.Dropped },
            "expected the extra transfers to be refused, got $outcomes",
        )
    }

    @Test
    fun `a smaller MTU still round-trips, in more chunks`() {
        val payload = Random(10).nextBytes(600)
        val (wideTx, wideRx) = codecPair(mtu = 247)
        val (narrowTx, narrowRx) = codecPair(mtu = 64)

        assertArrayEquals(payload, roundTrip(wideTx, wideRx, CmfCommand.ACTIVITY_DATA, payload))
        assertArrayEquals(payload, roundTrip(narrowTx, narrowRx, CmfCommand.ACTIVITY_DATA, payload))

        assertTrue(
            narrowTx.encode(CmfCommand.ACTIVITY_DATA, payload).size >
                wideTx.encode(CmfCommand.ACTIVITY_DATA, payload).size,
        )
    }

    @Test
    fun `garbage and unknown opcodes are dropped without throwing`() {
        val rx = CmfCodec().apply { sessionKey = key }

        assertEquals(
            CmfDropReason.MALFORMED_HEADER,
            (rx.decode(byteArrayOf(0x01, 0x02)) as CmfDecoded.Dropped).reason,
        )
        assertEquals(
            CmfDropReason.MALFORMED_HEADER,
            (rx.decode(ByteArray(20)) as CmfDecoded.Dropped).reason,
        )

        val unknown = CmfFrame.header(CmfCommand.BATTERY, 0, 1, 1)
        unknown[3] = 0x7e // clobber cmd1 into an opcode we do not know

        val decoded = rx.decode(unknown) as CmfDecoded.Dropped
        assertEquals(CmfDropReason.UNKNOWN_COMMAND, decoded.reason)

        // The opcodes survive: an unknown command is only identifiable by its numbers.
        assertEquals(0x7e5c, decoded.cmd1)
        assertEquals(0x0001, decoded.cmd2)
    }

    @Test
    fun `rekeying clears half-received chunks`() {
        val (tx, rx) = codecPair()
        val frames = tx.encode(CmfCommand.ACTIVITY_DATA, Random(11).nextBytes(4000))

        assertInstanceOf(CmfDecoded.Pending::class.java, rx.decode(frames[0]))
        rx.sessionKey = key // same bytes, but assignment means a new session

        assertEquals(
            CmfDropReason.CHUNK_OUT_OF_ORDER,
            (rx.decode(frames[1]) as CmfDecoded.Dropped).reason,
        )
    }
}
