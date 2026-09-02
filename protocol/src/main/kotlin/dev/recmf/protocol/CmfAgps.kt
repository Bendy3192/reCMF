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

    /**
     * Builds an almanac the watch will take out of MediaTek's own published orbits.
     *
     * The watch's receiver is a MediaTek part and the file it wants is MediaTek's EPO,
     * wrapped in a container of tag-and-length records. MediaTek publishes the orbits
     * themselves, openly and without an account; the official app republishes a three-day
     * slice of them. So this cuts the same slice.
     *
     * A MediaTek EPO file is a run of six-hour slots. Each slot is one satellite record of
     * 72 bytes per satellite, and the first three bytes of every record are the slot's
     * hour since the GPS epoch — which is what makes the layout readable without being
     * told it. This takes [SLOTS] slots from the six-hour boundary at or before
     * [nowHourSinceGpsEpoch] and wraps them as record 1.
     *
     * **How many satellites a slot holds is read, not assumed.** MediaTek publishes the
     * same orbits in more than one shape: `EPO.DAT` is a month of 32 GPS satellites, 2304
     * bytes to a slot, while `EPO_GR_3_N.DAT` is three days of 56 — GPS 1-32 and then
     * GLONASS 65-88 — at 4032. The official app's file is the second of those, and the
     * difference is not academic: where GPS is jammed, GLONASS may be the only
     * constellation a receiver can hear, and a GPS-only almanac helps it not at all. So
     * the slot size is found from where the hour first changes, and either file works.
     *
     * The trailing record is 32 ASCII characters that look like a checksum and are not
     * one the watch checks: it accepted a file whose orbits had been replaced wholesale
     * with that field left as it was, and then a file where it was zeroes. What the watch
     * does verify is the CRC32 in [transferRequest], which is computed here over whatever
     * is actually sent.
     *
     * @return null if [epoDat] is not a whole number of slots or does not cover now.
     */
    fun buildFromEpo(epoDat: ByteArray, nowHourSinceGpsEpoch: Int): ByteArray? {
        val slot = slotSize(epoDat) ?: return null
        if (epoDat.size % slot != 0 || slot % SATELLITE != 0) return null

        val satellites = slot / SATELLITE
        if (satellites != GPS_SATELLITES && satellites != COMBINED_SATELLITES) return null

        val slots = HashMap<Int, ByteArray>(epoDat.size / slot)
        for (at in epoDat.indices step slot) {
            slots[hourAt(epoDat, at)] = epoDat.copyOfRange(at, at + slot)
        }

        val start = nowHourSinceGpsEpoch - nowHourSinceGpsEpoch.mod(SLOT_HOURS)
        val wanted = (0 until SLOTS).map { start + it * SLOT_HOURS }
        // A file that stops short is worse than none: the receiver would trust orbits for
        // hours the file never covered. Insist on the whole run from now.
        val body = wanted
            .map { combined(slots[it] ?: return null) }
            .fold(ByteArray(0)) { all, b -> all + b }

        return recordHeader(1, body.size) + body +
            recordHeader(4, TRAILER.size) + TRAILER
    }

    /**
     * One slot in the shape the watch reads, whatever shape it arrived in.
     *
     * **The layout is positional, and this is what a GPS-only file gets wrong.** In the
     * file the official app sends, every slot is exactly fifty-six records: GPS 1-32 and
     * then GLONASS 65-88, each satellite at a fixed offset, with a satellite the file has
     * no orbit for written as an empty record *in its own place* rather than left out.
     * MediaTek's month of GPS is the same thing thirty-two records wide.
     *
     * Handing thirty-two-record slots to a reader expecting fifty-six does not give it
     * GPS and no GLONASS. It gives it the next slot's first twenty-four satellites read as
     * GLONASS, and every slot after the first starting in the wrong place — wrong orbits
     * for everything, which is worse than no almanac at all, because a receiver believes
     * an almanac. That is what a watch that will not fix outdoors looks like.
     *
     * So the GLONASS half is written as absent. The empty record is borrowed from the
     * slot itself rather than invented: `EPO.DAT` carries one in every slot — satellite 13
     * has no orbit in any of the 120 observed — and its trailing bytes change with the
     * hour, so a record copied from a different slot would carry another slot's tail.
     */
    private fun combined(slot: ByteArray): ByteArray {
        if (slot.size == COMBINED_SATELLITES * SATELLITE) return slot

        val absent = absentRecord(slot)
        var out = slot
        repeat(COMBINED_SATELLITES - GPS_SATELLITES) { out += absent }
        return out
    }

    /**
     * What this file writes for a satellite it has no orbit for.
     *
     * Taken from the slot when it has one, so the hour and whatever is computed from it
     * are genuine. Falling back to the hour and zeroes when every satellite in the slot
     * has data: it says the same thing in the same field, and the alternative — copying a
     * record belonging to another hour — would be a lie about which hour it covers.
     */
    private fun absentRecord(slot: ByteArray): ByteArray {
        for (at in slot.indices step SATELLITE) {
            if (slot[at + SATELLITE_ID] == 0.toByte()) return slot.copyOfRange(at, at + SATELLITE)
        }

        return ByteArray(SATELLITE).also {
            it[0] = slot[0]
            it[1] = slot[1]
            it[2] = slot[2]
        }
    }

    /**
     * How long one six-hour slot is, taken from where the hour first changes.
     *
     * Reading it beats trusting the file's length, which cannot tell the two shapes apart:
     * a three-day 56-satellite file is 48384 bytes, and that divides evenly by both 4032
     * and 2304. The hour does not lie — every satellite record in a slot carries the same
     * one, and the next slot's is six higher.
     *
     * @return null for a file with no second slot to find the boundary from, which is not
     *   one this can use anyway: it would cover six hours of the three days needed.
     */
    private fun slotSize(epoDat: ByteArray): Int? {
        if (epoDat.size < 2 * SATELLITE) return null

        val first = hourAt(epoDat, 0)
        var at = SATELLITE
        while (at + SATELLITE <= epoDat.size) {
            if (hourAt(epoDat, at) != first) return at
            at += SATELLITE
        }
        return null
    }

    /** The hour a satellite record belongs to: three bytes, little-endian, at its start. */
    private fun hourAt(epoDat: ByteArray, at: Int): Int =
        (epoDat[at].toInt() and 0xff) or
            ((epoDat[at + 1].toInt() and 0xff) shl 8) or
            ((epoDat[at + 2].toInt() and 0xff) shl 16)

    /** Hours from the GPS epoch, which is what an EPO block's first three bytes count. */
    fun hoursSinceGpsEpoch(epochSeconds: Long): Int =
        ((epochSeconds - GPS_EPOCH_SECONDS) / 3600).toInt()

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

    /**
     * Which shape [buildFromEpo] produces, so a watch holding an older one is given the
     * newer without waiting for the old to go stale.
     *
     * Bumped when the bytes change in a way that matters to the receiver rather than on
     * every release: an almanac is a megabyte-ish transfer over Bluetooth and re-sending
     * an identical one costs the wearer a minute and some battery for nothing.
     *
     * 1 was thirty-two records to a slot, which the watch read as the wrong satellites.
     * 2 is the fifty-six the watch expects.
     */
    const val FORMAT: Int = 2

    private const val START: Byte = 0x01
    private const val PADDING: Byte = 0xff.toByte()

    /** One satellite's predicted orbit for one six-hour slot. */
    private const val SATELLITE = 72
    private const val SLOT_HOURS = 6

    /** Where a record says which satellite it is for. Zero means it is for none. */
    private const val SATELLITE_ID = 3

    /** `EPO.DAT`: GPS 1-32. */
    private const val GPS_SATELLITES = 32

    /** What the watch reads: GPS 1-32 then GLONASS 65-88, each at a fixed offset. */
    private const val COMBINED_SATELLITES = 56

    /** Twelve slots is three days, which is the span the official app's file covers. */
    private const val SLOTS = 12

    /** 1980-01-06, in Unix seconds. Leap seconds are ignored: the slots are six hours. */
    private const val GPS_EPOCH_SECONDS = 315_964_800L

    /** Where a checksum would go, if the watch looked at one. */
    private val TRAILER = ByteArray(32) { '0'.code.toByte() }

    private fun recordHeader(tag: Int, length: Int): ByteArray =
        "%08x%08x".format(tag, length).toByteArray(Charsets.US_ASCII)

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
