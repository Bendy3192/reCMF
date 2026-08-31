/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * A watchface file, as far as it needs to be understood to send one.
 *
 * Two real files were read out of Bluetooth captures — the chunks that carry them are not
 * encrypted — and both begin the same way:
 *
 * ```
 * ?:u32 | 01 00 00 00 | name, null-terminated | ...
 * ```
 *
 * The four bytes at offset 4 are the version, and `01 00 00 00` is the **Watch Pro 2**
 * one. Gadgetbridge looks for `01 00 00 02` at offset 0 instead, which is the original CMF
 * Watch, and refuses a Pro 2 file before it transmits anything. The first four bytes differ
 * between files and are not a CRC32 of the rest; they are carried and not interpreted.
 *
 * A file **ends with its own first 36 bytes repeated**. That closing copy is where the file
 * stops, and the two lengths at offsets 24 and 28 are measured against it. See [prepare].
 *
 * Nothing here parses the *contents*. reCMF sends a file the watch will accept or refuses
 * one it will not; drawing a watchface is the watch's business.
 */
object CmfWatchfaceFile {

    /** Bytes 4..8 of a file built for this watch. */
    val VERSION_WATCH_PRO_2: ByteArray = byteArrayOf(0x01, 0x00, 0x00, 0x00)

    /** Bytes 4..8 of a file built for the original CMF Watch, which this one refuses. */
    val VERSION_WATCH_1: ByteArray = byteArrayOf(0x01, 0x00, 0x00, 0x02)

    private const val VERSION_OFFSET = 4
    private const val NAME_OFFSET = 8

    /** Long enough for any name seen, short enough that a wrong file fails here. */
    private const val NAME_LIMIT = 64

    /** Smaller than this and there is nothing to check, let alone install. */
    private const val MINIMUM_SIZE = 64

    /**
     * What a file says about itself, or why it will not be sent.
     *
     * A refusal names the reason in a form worth showing: someone who has just downloaded
     * a face for the wrong watch is better served by being told so than by a transfer that
     * fails halfway with the watch left holding half a face.
     */
    sealed interface Reading {
        data class Ok(val name: String, val size: Int) : Reading
        data object TooSmall : Reading
        data object ForTheOtherWatch : Reading
        data class UnknownVersion(val version: List<Int>) : Reading
        data object NoName : Reading
    }

    fun read(bytes: ByteArray): Reading {
        if (bytes.size < MINIMUM_SIZE) return Reading.TooSmall

        val version = bytes.copyOfRange(VERSION_OFFSET, VERSION_OFFSET + 4)
        if (version.contentEquals(VERSION_WATCH_1)) return Reading.ForTheOtherWatch
        if (!version.contentEquals(VERSION_WATCH_PRO_2)) {
            return Reading.UnknownVersion(version.map { it.toInt() and 0xff })
        }

        val end = (NAME_OFFSET until minOf(bytes.size, NAME_OFFSET + NAME_LIMIT))
            .firstOrNull { bytes[it] == 0.toByte() }
            ?: return Reading.NoName

        val name = String(bytes, NAME_OFFSET, end - NAME_OFFSET, StandardCharsets.UTF_8)
        return if (name.isEmpty()) Reading.NoName else Reading.Ok(name, bytes.size)
    }

    /**
     * `DATA_TRANSFER_WATCHFACE_INIT_2_ALT_REQUEST` (`ffff/9075`): thirteen little-endian
     * bytes that say what is arriving and what it displaces.
     *
     * ```
     * mode:u8 | replacedId:u32 | newId:u32 | size:u32
     * ```
     *
     * Read out of a decrypted capture as `03 6e010000 43010000 48290100` — mode 3,
     * replacing 366, installing 323, 76104 bytes — with every field confirmed from
     * outside the frame: 366 was in that slot before, 323 was in it after, and the watch's
     * own chunk requests ran to offset 76104 exactly.
     *
     * [replacedId] is the field Gadgetbridge fills with a random number. It is not a
     * random number: the watch holds a fixed six faces, so an install is always a
     * replacement, and this says which one goes.
     */
    fun transferRequest(replacedId: Int, newId: Int, size: Int): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put(MODE_REPLACE)
            .putInt(replacedId)
            .putInt(newId)
            .putInt(size)
            .array()

    /**
     * The only mode seen. `0x02` is reported elsewhere as "upload a new one" rather than
     * "replace", but this watch has six slots and no room for a seventh, and an untested
     * mode is not worth sending to a device that has to be re-paired when it sulks.
     */
    private const val MODE_REPLACE: Byte = 0x03

    /**
     * The `DATA_CHUNK_REQUEST_WATCHFACE` (`ffff/a064`) payload: where the watch wants the
     * next stretch from, how much of it, and how far along it thinks it is.
     *
     * Big-endian, unlike the request that started the transfer. Observed as 3072-byte
     * asks, walking the file from zero, with the last one landing exactly on the size
     * announced.
     */
    data class ChunkRequest(val offset: Int, val length: Int, val percent: Int)

    fun parseChunkRequest(payload: ByteArray): ChunkRequest? {
        if (payload.size < 9) return null

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val offset = buffer.int
        val length = buffer.int
        val percent = buffer.get().toInt() and 0xff

        // A watch asking for a negative offset or nothing at all is a watch reCMF has
        // misunderstood, and reading the file at that offset would be the next mistake.
        if (offset < 0 || length <= 0) return null

        return ChunkRequest(offset, length, percent)
    }

    /**
     * The fixed part at the front, and the same 36 bytes again at the very end.
     *
     * The whole layout, confirmed against the file the watch accepted:
     *
     * ```
     * header 36 | name block | element table | resources | header again, 36
     * ```
     *
     * So the length at [CONTENT_SIZE_OFFSET] is everything before that closing copy, and the
     * length at [RESOURCE_SIZE_OFFSET] is the resources alone; their difference is the
     * element table, which is also the offset the first element points at.
     */
    private const val HEADER_BYTES = 36

    /** Where the header says how much file precedes its closing copy. */
    private const val CONTENT_SIZE_OFFSET = 24

    /** Where it says how much of that is images. */
    private const val RESOURCE_SIZE_OFFSET = 28

    /** The length at [CONTENT_SIZE_OFFSET], or null from something too short to hold one. */
    fun declaredContentSize(bytes: ByteArray): Int? =
        if (bytes.size < CONTENT_SIZE_OFFSET + 4) {
            null
        } else {
            ByteBuffer.wrap(bytes, CONTENT_SIZE_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

    /**
     * Whether a file's own header accounts for it exactly.
     *
     * Two things have to agree, and both are cheap: the length at offset 24 has to leave
     * room for the closing copy and nothing else, and that copy has to actually be there.
     * The accepted file satisfies both; so does a repaired download; nothing else seen does.
     */
    private fun wellFormed(bytes: ByteArray): Boolean {
        val declared = declaredContentSize(bytes) ?: return false
        if (declared < HEADER_BYTES || declared + HEADER_BYTES != bytes.size) return false
        return bytes.copyOfRange(declared, bytes.size).contentEquals(bytes.copyOf(HEADER_BYTES))
    }

    /**
     * What shape a file is in, and the bytes to send because of it.
     *
     * A face going around as a download turned out to be 1044 bytes too long, and the extra
     * bytes were not on the end: they were **the CRC32 that every message of a Bluetooth
     * transfer ends with**, left in when somebody rebuilt the file out of a capture. 261
     * messages, four bytes each. reCMF made the same mistake reassembling a file of its own,
     * which is the only reason it was recognisable.
     *
     * So a file that does not account for itself is walked message by message and its
     * checksums pulled out — and the result is only used if it then accounts for itself
     * exactly. That last check is what makes this safe to do at all: the repair either lands
     * on a file shaped like the one the watch accepted, or it is thrown away.
     */
    sealed interface Packaging {
        /** The bytes to put on the wire. */
        val bytes: ByteArray

        /** Already the shape the watch was sent: nothing to do. */
        class Device(override val bytes: ByteArray) : Packaging

        /** A capture's leftover checksums pulled back out. */
        class Repaired(override val bytes: ByteArray, val from: Int) : Packaging

        /** Neither, and no repair that lands on a sound file. Sent as it came. */
        class Unexplained(override val bytes: ByteArray) : Packaging
    }

    fun prepare(bytes: ByteArray): Packaging {
        if (bytes.size < MINIMUM_SIZE) return Packaging.Unexplained(bytes)
        if (wellFormed(bytes)) return Packaging.Device(bytes)

        val stripped = withoutMessageChecksums(bytes)
        return if (stripped != null && wellFormed(stripped)) {
            Packaging.Repaired(stripped, from = bytes.size)
        } else {
            Packaging.Unexplained(bytes)
        }
    }

    /**
     * Reads the file as the sequence of messages it once was and drops each one's checksum,
     * or gives up.
     *
     * Message lengths are not fixed — the watch asks for the file in 3072-byte stretches and
     * those do not divide by the 224 bytes a full message carries, so every stretch ends
     * short and so does the file. Rather than model that, each message's length is found by
     * extending a CRC32 a byte at a time and watching for the four bytes that follow to be
     * it. The longest length that matches is taken: a shorter accidental match is possible
     * once in four billion, and preferring the longer one costs nothing.
     */
    private fun withoutMessageChecksums(bytes: ByteArray): ByteArray? {
        val out = ByteArray(bytes.size)
        var read = 0
        var written = 0

        while (read < bytes.size) {
            val room = minOf(MAX_MESSAGE_BYTES, bytes.size - read - CHECKSUM_BYTES)
            if (room <= 0) return null

            val crc = CRC32()
            var length = -1
            for (candidate in 1..room) {
                crc.update(bytes[read + candidate - 1].toInt())
                if (checksumAt(bytes, read + candidate) == crc.value) length = candidate
            }
            if (length < 0) return null

            bytes.copyInto(out, written, read, read + length)
            written += length
            read += length + CHECKSUM_BYTES
        }

        return out.copyOf(written)
    }

    private fun checksumAt(bytes: ByteArray, at: Int): Long =
        ByteBuffer.wrap(bytes, at, CHECKSUM_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL

    /** The most a single message of a transfer was seen to carry. */
    private const val MAX_MESSAGE_BYTES = 244

    private const val CHECKSUM_BYTES = 4
}
