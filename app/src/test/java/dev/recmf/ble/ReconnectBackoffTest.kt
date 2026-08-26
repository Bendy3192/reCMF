package dev.recmf.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReconnectBackoffTest {
    private fun noJitter(initial: Long = 1_000, max: Long = 60_000) =
        ReconnectBackoff(initialDelayMillis = initial, maxDelayMillis = max, jitterFraction = 0.0)

    @Test
    fun `delays double up to the ceiling and stay there`() {
        val backoff = noJitter()

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            List(8) { backoff.nextDelayMillis() },
        )
    }

    @Test
    fun `a very long outage does not overflow into a short delay`() {
        // Naive shifting wraps around 2^63 and would produce a negative or tiny delay,
        // turning an hours-long outage into a hot reconnect loop.
        val backoff = noJitter()
        repeat(200) { backoff.nextDelayMillis() }

        assertEquals(60_000L, backoff.nextDelayMillis())
    }

    @Test
    fun `reset returns to the initial delay`() {
        val backoff = noJitter()
        repeat(5) { backoff.nextDelayMillis() }

        backoff.reset()

        assertEquals(0, backoff.attempt)
        assertEquals(1_000L, backoff.nextDelayMillis())
    }

    @Test
    fun `jitter only ever shortens the wait`() {
        val backoff = ReconnectBackoff(
            initialDelayMillis = 1_000,
            maxDelayMillis = 60_000,
            jitterFraction = 0.2,
            random = Random(42),
        )

        repeat(20) {
            val delay = backoff.nextDelayMillis()
            assertTrue(delay in 800..60_000, "delay $delay left the expected band")
        }
    }

    @Test
    fun `jitter actually varies between instances`() {
        fun firstDelay(seed: Int) = ReconnectBackoff(
            initialDelayMillis = 10_000,
            maxDelayMillis = 60_000,
            jitterFraction = 0.5,
            random = Random(seed),
        ).nextDelayMillis()

        // Without jitter every client would reconnect on the same tick after a
        // Bluetooth restart and collide repeatedly.
        assertTrue((1..20).map(::firstDelay).distinct().size > 1)
    }

    @Test
    fun `nonsensical configuration is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff(initialDelayMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectBackoff(initialDelayMillis = 10_000, maxDelayMillis = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectBackoff(jitterFraction = 1.5)
        }
    }
}
