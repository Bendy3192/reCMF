/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.ble

import kotlin.random.Random

/**
 * Delay schedule for reconnect attempts.
 *
 * A watch goes out of range for hours at a time — someone leaves it on the nightstand —
 * and a fixed short retry would keep the radio and this process busy the whole time,
 * which is how a companion app ends up killed for battery use. So the delay doubles up
 * to a ceiling and stays there.
 *
 * Jitter matters for a different reason: without it, every reconnect after a Bluetooth
 * restart lines up on the same tick and they collide repeatedly.
 *
 * Pure and deterministic given [random], so the schedule is unit-testable.
 */
class ReconnectBackoff(
    private val initialDelayMillis: Long = 2_000,
    private val maxDelayMillis: Long = 5 * 60_000,
    private val jitterFraction: Double = 0.2,
    private val random: Random = Random.Default,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must not be below initialDelayMillis" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be within 0..1" }
    }

    var attempt: Int = 0
        private set

    /** Call after a successful connection so the next outage starts from the short delay. */
    fun reset() {
        attempt = 0
    }

    /** Returns the delay to wait before the next attempt, and advances the schedule. */
    fun nextDelayMillis(): Long {
        val exponential = if (attempt >= EXPONENT_CEILING) {
            maxDelayMillis
        } else {
            (initialDelayMillis shl attempt).coerceAtMost(maxDelayMillis)
        }
        attempt++

        if (jitterFraction == 0.0) return exponential

        val spread = (exponential * jitterFraction).toLong()
        if (spread <= 0) return exponential

        // Jitter downward only: never wait longer than the ceiling promises.
        return exponential - random.nextLong(spread + 1)
    }

    private companion object {
        /** Past this, `initialDelay shl attempt` would overflow before it could be clamped. */
        const val EXPONENT_CEILING = 40
    }
}
