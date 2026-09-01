/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Layout ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
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
     * 0  u32 BE  time, epoch seconds
     * 4  i32 BE  latitude,  degrees times ten million
     * 8  i32 BE  longitude, degrees times ten million
     * ```
     *
     * Big-endian, unlike most payload bodies here — the protocol is mixed and this is one
     * of the ones that is not little-endian.
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
     * what makes its fixes near-instant; that transfer's opening command is not known to
     * this project or to Gadgetbridge, whose own AGPS support answers the watch's requests
     * for file chunks but never sends the request that would start one.
     *
     * @param latitude degrees north, negative for south
     * @param longitude degrees east, negative for west
     */
    fun gpsCoords(latitude: Double, longitude: Double, epochSeconds: Long): ByteArray =
        ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putInt(epochSeconds.toInt())
            .putInt(scaled(latitude))
            .putInt(scaled(longitude))
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

    const val PAYLOAD_SIZE: Int = 12

    /** Degrees as whole numbers, scaled by ten million. */
    private const val DEGREE_SCALE = 10_000_000.0

    private fun scaled(degrees: Double): Int = (degrees * DEGREE_SCALE).roundToInt()
}
