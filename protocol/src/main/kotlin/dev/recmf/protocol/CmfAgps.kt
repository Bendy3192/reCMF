/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Uploading predicted satellite orbits — an EPO file — to the watch.
 *
 * This is what makes the difference between a GPS fix in seconds and one that never
 * arrives. The watch has its own receiver, and a receiver starting cold has to read the
 * satellites' orbits off the satellites themselves at fifty bits a second. Given the
 * orbits in advance it only has to hear a signal and do the arithmetic.
 *
 * **Read off the wire rather than ported.** Gadgetbridge names all six of these opcodes,
 * answers the watch's requests for chunks, and recognises the file by its magic bytes —
 * but it never sends [transferRequest], so no transfer ever starts there and its own
 * parser is a `// TODO`. The layout below comes from a capture of the official app
 * uploading a real file, decrypted with the watch's own app secret.
 *
 * The exchange, which is the same shape as the watchface transfer:
 *
 * ```
 * TX 905e  01 | size:u32 BE | crc32:u32 LE | ff x 31      the 40 bytes of transferRequest
 * RX a05e  01                                             accepted
 * RX a05f  offset:u32 BE | length:u32 BE | percent:u8     asked in 3072-byte stretches
 * TX 905f  <the bytes at that offset>                     unencrypted, CRC32 per message
 * RX a060  01                                             the watch has it all
 * TX 9060  a5
 * ```
 */
object CmfAgps {

    /**
     * What an EPO file looks like from the outside: twelve ASCII characters reading
     * `000000010000`.
     *
     * Those are not arbitrary. The file is a run of records, each headed by eight ASCII
     * hex digits of tag and eight of length, so the magic is simply the header of the
     * first record — tag 1, and a length whose top half is zero. Gadgetbridge checks the
     * same twelve bytes.
     */
    val MAGIC: ByteArray = "000000010000".toByteArray(Charsets.US_ASCII)

    /**
     * Whether these bytes are an EPO file this can send.
     *
     * The magic alone would pass a truncated download, so the records are walked as well:
     * a real file's tags and lengths tile it exactly, with nothing left over. One observed
     * file was 97872 bytes of four records — 48384, 25128 and 24264 bytes of orbit data,
     * then 32 bytes of ASCII that look like a checksum of some kind, computed by a rule
     * that is not any obvious digest of any obvious span.
     */
    fun looksLikeEpo(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return false

        var offset = 0
        while (offset < bytes.size) {
            if (offset + RECORD_HEADER > bytes.size) return false
            val length = asciiHex(bytes, offset + TAG_DIGITS, LENGTH_DIGITS) ?: return false
            if (length < 0) return false
            offset += RECORD_HEADER + length
        }
        return offset == bytes.size
    }

    /**
     * `DATA_TRANSFER_AGPS_INIT_REQUEST`: what is coming and how to know it arrived whole.
     *
     * Forty bytes, of which nine carry anything: a leading `01`, the size big-endian, and
     * the file's CRC32 little-endian. The two orders disagree inside one payload, which is
     * not a transcription slip — this protocol mixes them freely, and the CRC is written
     * the way every other CRC on this wire is written.
     *
     * The rest is `0xff`. Whether that is padding or a field the official app leaves unset
     * is not known; it is sent as observed rather than as guessed, since a fixed-size
     * record filled with `ff` is exactly what the watch accepted.
     */
    fun transferRequest(size: Int, crc32: Long): ByteArray {
        val buf = ByteBuffer.allocate(REQUEST_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(START)
        buf.putInt(size)
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(crc32.toInt())
        while (buf.hasRemaining()) buf.put(PADDING)
        return buf.array()
    }

    /** The whole file's CRC32, as [transferRequest] wants it. */
    fun checksum(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** The watch's `a05f` ask, which is the same nine bytes as a watchface's. */
    fun parseChunkRequest(payload: ByteArray): CmfWatchfaceFile.ChunkRequest? =
        CmfWatchfaceFile.parseChunkRequest(payload)

    /** What the watch sends and expects at the end of a transfer. */
    const val FINISHED: Byte = 0x01
    val FINISH_ACK: ByteArray = byteArrayOf(0xa5.toByte())

    /** The reply to [transferRequest]: one byte, and only `01` means carry on. */
    const val ACCEPTED: Byte = 0x01

    const val REQUEST_SIZE: Int = 40

    private const val START: Byte = 0x01
    private const val PADDING: Byte = 0xff.toByte()

    private const val TAG_DIGITS = 8
    private const val LENGTH_DIGITS = 8
    private const val RECORD_HEADER = TAG_DIGITS + LENGTH_DIGITS

    /** A field of ASCII hex digits, or null if it is not one. */
    private fun asciiHex(bytes: ByteArray, at: Int, digits: Int): Int? {
        var value = 0
        for (i in at until at + digits) {
            val digit = Character.digit(bytes[i].toInt().toChar(), 16)
            if (digit < 0) return null
            value = value * 16 + digit
        }
        return value
    }
}
