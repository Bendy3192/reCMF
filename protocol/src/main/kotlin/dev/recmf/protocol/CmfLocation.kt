/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object CmfLocation {

    /**
     * `GPS_COORDS`: where the phone thinks it is, so the watch's own receiver has
     * somewhere to start looking.
     *
     * ```
     * 0   u32 BE  time, epoch seconds, UTC
     * 4   i32 BE  longitude, degrees times ten million
     * 8   i32 BE  latitude,  degrees times ten million
     * 12  u32 BE  0x6f8531fd, always
     * ```
     *
     * Big-endian, unlike most payload bodies here — the protocol is mixed and this is one
     * of the ones that is not little-endian.
     *
     * **Longitude comes first, and this was got wrong for a long time.** The first version
     * of this file wrote latitude first, over sixteen bytes' worth of guess in twelve, and
     * the watch never fixed. Two captures of the official app settle it: the second field
     * held the writer's longitude and the third their latitude, a day apart and from
     * slightly different places, and the time field matched the capture's own clock to the
     * second. Written the other way round, a position in Moscow is sent as one in
     * Uzbekistan — two thousand kilometres out, which is worse for a receiver than being
     * told nothing, since it then searches for satellites that are not overhead.
     *
     * It is the same order the watch uses for the points of a recorded track, which is the
     * corroboration: one convention in both directions, unusual as it looks written down.
     *
     * The last four bytes are sent as observed. They were identical in both captures —
     * different days, different positions — so they are neither a checksum of what
     * precedes them nor anything derived from it. Read as a coordinate they would be
     * 187.1 metres, which is about the elevation of the city in question and would make
     * them an altitude the app never updates; read as anything else they are unexplained.
     * Either way the watch was sent exactly these bytes and did what was wanted.
     *
     * This is a seed, not a track. The watch has its own GNSS receiver and does not need
     * the phone to record a route; what it needs is a hint. A receiver starting from
     * nothing has to work out which satellites are even overhead, and it does that by
     * downloading their orbits from the satellites themselves at fifty bits a second —
     * minutes under open sky, and effectively never between buildings. Told roughly where
     * and when it is, it can search for the handful of satellites that should be above it
     * rather than all of them.
     *
     * It is not the whole answer. The official app also uploads an almanac file, which is
     * what makes its fixes near-instant; see `CmfAgps`, which sends one.
     *
     * @param latitude degrees north, negative for south
     * @param longitude degrees east, negative for west
     */
    fun gpsCoords(latitude: Double, longitude: Double, epochSeconds: Long): ByteArray =
        ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putInt(epochSeconds.toInt())
            .putInt(scaled(longitude))
            .putInt(scaled(latitude))
            .putInt(TRAILER)
            .array()

    /**
     * Whether a position is worth sending at all.
     *
     * Null Island — zero latitude and zero longitude — is what a location fix looks like
     * when it failed rather than a place anybody is, and Gadgetbridge skips it for that
     * reason. Sending it would seed the receiver with a hint that is wrong by up to half
     * the planet, which is worse than sending nothing.
     */
    fun worthSending(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    const val PAYLOAD_SIZE: Int = 16

    /** The fourth field, whose meaning is not known. See [gpsCoords]. */
    private const val TRAILER: Int = 0x6f8531fd

    /** Degrees as whole numbers, scaled by ten million. */
    private const val DEGREE_SCALE = 10_000_000.0

    private fun scaled(degrees: Double): Int = (degrees * DEGREE_SCALE).roundToInt()
}
