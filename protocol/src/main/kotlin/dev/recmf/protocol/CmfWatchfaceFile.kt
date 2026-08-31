/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

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
     * The whole layout, confirmed against the one file known to have been accepted:
     *
     * ```
     * header 36 | name block | element table | resources | header again, 36
     * ```
     *
     * So the length at [CONTENT_SIZE_OFFSET] is everything before that closing copy, the
     * length at [RESOURCE_SIZE_OFFSET] is the resources alone, and their difference is the
     * element table. On the accepted file all three agree exactly: 76068 and 74796 in a file
     * of 76104, with the closing copy at 76068 and the first element pointing at 1272.
     */
    private const val HEADER_BYTES = 36

    /** Where the header says how much file precedes its closing copy. */
    private const val CONTENT_SIZE_OFFSET = 24

    /** Where it says how much of that is images. */
    private const val RESOURCE_SIZE_OFFSET = 28

    /** The length at [CONTENT_SIZE_OFFSET], or null from something too short to hold one. */
    fun declaredContentSize(bytes: ByteArray): Int? = readInt(bytes, CONTENT_SIZE_OFFSET)

    private fun readInt(bytes: ByteArray, at: Int): Int? =
        if (bytes.size < at + 4) {
            null
        } else {
            ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

    /**
     * What shape a file is in, and the bytes to send because of it.
     *
     * A face downloaded from a watchface site turned out to be a device file with four
     * bytes stuck on the end and two stale lengths in its header — its closing header copy
     * sits 40 bytes from the end rather than 36, and the length at offset 24 is a thousand
     * bytes short of where that copy actually is. The closing copy is the reliable mark of
     * where the file ends, because it is the one thing both files carry in the same form.
     *
     * So a file is cut to end just after its closing copy, and the two lengths are rewritten
     * from where that copy was found — keeping their difference, which is the element table
     * and is what the first element's offset has to agree with. A file already in that shape
     * comes through untouched, which is what the accepted one does.
     */
    sealed interface Packaging {
        /** The bytes to put on the wire. */
        val bytes: ByteArray

        /** Already the shape the watch was sent: nothing to do. */
        class Device(override val bytes: ByteArray) : Packaging

        /** Cut to its closing header copy, with the lengths in front rewritten to match. */
        class Repaired(override val bytes: ByteArray, val from: Int, val declared: Int) : Packaging

        /** No closing copy to measure from. Sent as it came, because guessing is worse. */
        class Unexplained(override val bytes: ByteArray) : Packaging
    }

    fun prepare(bytes: ByteArray): Packaging {
        if (bytes.size < MINIMUM_SIZE) return Packaging.Unexplained(bytes)

        val declared = declaredContentSize(bytes) ?: return Packaging.Unexplained(bytes)
        val resources = readInt(bytes, RESOURCE_SIZE_OFFSET) ?: return Packaging.Unexplained(bytes)
        val table = declared - resources

        val closing = closingCopy(bytes)
        if (closing < 0) return Packaging.Unexplained(bytes)

        if (closing == declared && closing + HEADER_BYTES == bytes.size) {
            return Packaging.Device(bytes)
        }

        // The table has to survive the rewrite: the first element's offset is the table's
        // own length, so moving the two figures without keeping their difference would
        // point the watch at the wrong byte.
        if (table <= 0 || table >= closing) return Packaging.Unexplained(bytes)

        val repaired = bytes.copyOf(closing + HEADER_BYTES)
        ByteBuffer.wrap(repaired).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CONTENT_SIZE_OFFSET, closing)
            .putInt(RESOURCE_SIZE_OFFSET, closing - table)
        return Packaging.Repaired(repaired, from = bytes.size, declared = declared)
    }

    /**
     * Where the file's own first 36 bytes appear again, at or after the last place they
     * could be. The last such position is taken, so four bytes of somebody's checksum
     * stuck on the end move the answer rather than hide it.
     */
    private fun closingCopy(bytes: ByteArray): Int {
        val head = bytes.copyOf(HEADER_BYTES)
        for (at in bytes.size - HEADER_BYTES downTo HEADER_BYTES) {
            if (bytes.copyOfRange(at, at + HEADER_BYTES).contentEquals(head)) return at
        }
        return -1
    }
}
