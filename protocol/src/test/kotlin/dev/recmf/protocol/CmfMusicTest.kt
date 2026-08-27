package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CmfMusicTest {

    private fun payload(track: String = "Track", artist: String = "Artist") =
        CmfMusic.payload(MusicPlaybackState.PLAYING, volume = 7, maxVolume = 15, track, artist)

    @Test
    fun `the payload is the documented shape`() {
        val bytes = payload()

        assertEquals(CmfMusic.PAYLOAD_SIZE, bytes.size)
        assertEquals(2, bytes[0].toInt()) // playing
        assertEquals(7, bytes[1].toInt())
        assertEquals(15, bytes[2].toInt())
    }

    @Test
    fun `track and artist sit in their own fields`() {
        val bytes = payload(track = "Yellow", artist = "Coldplay")

        val trackField = bytes.copyOfRange(3, 3 + CmfMusic.TEXT_BYTES)
        val artistField = bytes.copyOfRange(3 + CmfMusic.TEXT_BYTES, CmfMusic.PAYLOAD_SIZE)

        assertEquals("Yellow", String(trackField.takeWhile { it != 0.toByte() }.toByteArray()))
        assertEquals("Coldplay", String(artistField.takeWhile { it != 0.toByte() }.toByteArray()))
    }

    @Test
    fun `a text that fills its field still ends in a zero`() {
        // Cut to 63 rather than 64, so a watch reading the field as a C string cannot run
        // out of it and into the artist that follows.
        val long = "x".repeat(200)
        val bytes = CmfMusic.payload(MusicPlaybackState.PLAYING, 0, 15, long, long)

        assertEquals(0, bytes[3 + CmfMusic.TEXT_BYTES - 1].toInt())
        assertEquals(0, bytes[CmfMusic.PAYLOAD_SIZE - 1].toInt())
        assertEquals(CmfMusic.PAYLOAD_SIZE, bytes.size)
    }

    @Test
    fun `a Cyrillic title is cut on a character boundary`() {
        val bytes = CmfMusic.payload(MusicPlaybackState.PLAYING, 0, 15, "Оды".repeat(30), "")
        val field = bytes.copyOfRange(3, 3 + CmfMusic.TEXT_BYTES)
        val text = String(field.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)

        assertEquals(false, '�' in text)
    }

    @Test
    fun `nothing playing is a state of its own, not a paused nothing`() {
        val bytes = CmfMusic.payload(MusicPlaybackState.NOTHING, 0, 15, "", "")

        assertEquals(0, bytes[0].toInt())
    }

    @Test
    fun `every button the watch sends is recognised`() {
        val expected = mapOf(
            byteArrayOf(0x01, 0x01).toList() to MusicButton.PLAY,
            byteArrayOf(0x01, 0x00).toList() to MusicButton.PAUSE,
            byteArrayOf(0x02, 0x01).toList() to MusicButton.NEXT,
            byteArrayOf(0x02, 0x00).toList() to MusicButton.PREVIOUS,
            byteArrayOf(0x03, 0x01).toList() to MusicButton.VOLUME_UP,
            byteArrayOf(0x03, 0x00).toList() to MusicButton.VOLUME_DOWN,
        )

        for ((bytes, button) in expected) {
            assertEquals(button, CmfMusic.parseButton(bytes.toByteArray()), "for $bytes")
        }
    }

    @Test
    fun `an unknown combination of known halves is not guessed at`() {
        // Action 4 does not exist, and neither does direction 2. Mapping either onto the
        // nearest thing would press a button the wearer did not.
        assertNull(CmfMusic.parseButton(byteArrayOf(0x04, 0x01)))
        assertNull(CmfMusic.parseButton(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun `a short button payload is refused`() {
        assertNull(CmfMusic.parseButton(byteArrayOf(0x01)))
        assertNull(CmfMusic.parseButton(ByteArray(0)))
    }
}
