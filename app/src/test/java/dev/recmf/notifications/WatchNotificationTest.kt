package dev.recmf.notifications

import dev.recmf.protocol.truncateToUtf8Bytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class WatchNotificationTest {
    private fun notification(title: String = "Ivan", body: String = "hello") = WatchNotification(
        icon = NotificationIcon.TELEGRAM,
        title = title,
        body = body,
        whenEpochSeconds = 0x01020304,
    )

    @Test
    fun `the payload matches the documented layout`() {
        val payload = notification().toPayload()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        assertEquals(NotificationIcon.TELEGRAM.code, buf.get())
        assertEquals(0, buf.get())
        assertEquals(0x01020304, buf.int)
        assertEquals(4, buf.get().toInt())

        val title = ByteArray(4).also { buf.get(it) }
        assertEquals("Ivan", String(title, StandardCharsets.UTF_8))

        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        assertEquals("hello", String(body, StandardCharsets.UTF_8))
    }

    @Test
    fun `Cyrillic text is never cut mid-character`() {
        // Each Cyrillic letter is two bytes, so a naive cut at 20 bytes lands inside
        // one and the watch would render the rest of the line as garbage.
        val title = "Иван Петрович Сидоров"
        val truncated = title.truncateToUtf8Bytes(WatchNotification.MAX_TITLE_BYTES)

        assertTrue(truncated.size <= WatchNotification.MAX_TITLE_BYTES)
        val decoded = String(truncated, StandardCharsets.UTF_8)
        assertTrue(title.startsWith(decoded), "'$decoded' is not a prefix of the original")
        assertTrue('�' !in decoded, "decoded to a replacement character: $decoded")
    }

    @Test
    fun `an over-long body is truncated, not rejected`() {
        val payload = notification(body = "x".repeat(500)).toPayload()

        assertEquals(
            WatchNotification.HEADER_SIZE + 4 + WatchNotification.MAX_BODY_BYTES,
            payload.size,
        )
    }

    @Test
    fun `known apps get their own icon and the rest fall back`() {
        assertEquals(NotificationIcon.TELEGRAM, NotificationIcon.forPackage("org.telegram.messenger"))
        assertEquals(NotificationIcon.WHATSAPP, NotificationIcon.forPackage("com.whatsapp"))
        assertEquals(NotificationIcon.UNKNOWN, NotificationIcon.forPackage("com.example.something"))
    }
}
