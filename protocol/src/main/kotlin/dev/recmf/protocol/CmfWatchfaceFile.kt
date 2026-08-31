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
 * At offset 24 there is a length. On the one file known to have been accepted — read byte
 * for byte off the wire while the official app installed it — it is 76068 against a file of
 * 76104, exactly [HEADER_BYTES] short of the whole. A file downloaded from a watchface site
 * says 57137 against 58217 and ends with its own first 36 bytes repeated, which the accepted
 * file does not. See [prepare].
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
     * The fixed part at the front, before anything that varies with the face.
     *
     * Thirty-six bytes: the four unexplained ones, the version, the name in twelve, four
     * zeroes, a `0a`, and then two lengths and three words that differ per file. The name
     * appears a second time at offset 45, which is why a face can be recognised without
     * knowing any of this.
     */
    private const val HEADER_BYTES = 36

    /** Where the header says how much file follows it. */
    private const val CONTENT_SIZE_OFFSET = 24

    /** The length at [CONTENT_SIZE_OFFSET], or null from something too short to hold one. */
    fun declaredContentSize(bytes: ByteArray): Int? =
        if (bytes.size < HEADER_BYTES) {
            null
        } else {
            ByteBuffer.wrap(bytes, CONTENT_SIZE_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

    /**
     * What a file's length says about how it is packaged, and the bytes to send because of it.
     *
     * A face installed by the official app was [HEADER_BYTES] longer than the length in its
     * own header — header plus content, and nothing after. A face downloaded from a site was
     * 1044 bytes longer than that, and those bytes end with the file's own first 36 repeated
     * and four more; the accepted file carries no such thing. So the download is a wrapper
     * around a device file, and the wrapper is what the watch was never sent.
     *
     * This is one known-good file against one known-refused one, which is why the third case
     * exists: a file that matches neither shape is sent whole and said to be unrecognised,
     * rather than trimmed on a rule that has been seen to hold exactly once.
     */
    sealed interface Packaging {
        /** The bytes to put on the wire. */
        val bytes: ByteArray

        /** Header plus the content it declares, which is the shape the watch was sent. */
        class Device(override val bytes: ByteArray) : Packaging

        /** A wrapper recognised by its repeated header and cut back to the file inside. */
        class Trimmed(override val bytes: ByteArray, val from: Int) : Packaging

        /** Neither shape. Sent as it came, because guessing at it would be worse. */
        class Unexplained(override val bytes: ByteArray, val declared: Int) : Packaging
    }

    fun prepare(bytes: ByteArray): Packaging {
        val declared = declaredContentSize(bytes) ?: return Packaging.Unexplained(bytes, 0)
        if (declared < 0 || declared > bytes.size) return Packaging.Unexplained(bytes, declared)

        val whole = declared + HEADER_BYTES
        if (whole == bytes.size) return Packaging.Device(bytes)

        return if (whole in MINIMUM_SIZE until bytes.size && carriesWrapper(bytes)) {
            Packaging.Trimmed(bytes.copyOf(whole), bytes.size)
        } else {
            Packaging.Unexplained(bytes, declared)
        }
    }

    /**
     * Whether the file ends with its own header again.
     *
     * Four bytes follow that repeat, and they are not a CRC32 of the file with or without
     * them, of the content, or of anything else tried. They are the wrapper's business and
     * are dropped with it. Gadgetbridge looks for the same repeat — it expects the name 28
     * bytes from the end — which says the wrapper is what watchface sites hand out.
     */
    private fun carriesWrapper(bytes: ByteArray): Boolean {
        val end = bytes.size - WRAPPER_BYTES
        if (end < HEADER_BYTES) return false
        return bytes.copyOfRange(end, end + HEADER_BYTES).contentEquals(bytes.copyOf(HEADER_BYTES))
    }

    /** The repeated header and the four bytes after it. */
    private const val WRAPPER_BYTES = HEADER_BYTES + 4
}
