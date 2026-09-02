package dev.recmf.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AiEndpointTest {

    @Test
    fun `a bare host gets the path`() {
        assertEquals("https://api.perplexity.ai/chat/completions", AiEndpoint.of("https://api.perplexity.ai"))
    }

    @Test
    fun `a trailing slash is not a second path segment`() {
        assertEquals("https://api.perplexity.ai/chat/completions", AiEndpoint.of("https://api.perplexity.ai/"))
    }

    @Test
    fun `a version segment is kept`() {
        // What OpenAI's own documentation shows, and what somebody would paste from it.
        assertEquals("https://api.openai.com/v1/chat/completions", AiEndpoint.of("https://api.openai.com/v1"))
    }

    @Test
    fun `a whole endpoint is taken at its word`() {
        val whole = "https://openrouter.ai/api/v1/chat/completions"

        assertEquals(whole, AiEndpoint.of(whole))
    }

    @Test
    fun `something running on the same desk works too`() {
        assertEquals("http://127.0.0.1:1234/v1/chat/completions", AiEndpoint.of("http://127.0.0.1:1234/v1"))
    }

    @Test
    fun `whitespace around a pasted url is not the user's problem`() {
        assertEquals("https://api.perplexity.ai/chat/completions", AiEndpoint.of("  https://api.perplexity.ai  "))
    }

    @Test
    fun `an unfilled field is nothing rather than an error`() {
        assertNull(AiEndpoint.of(""))
        assertNull(AiEndpoint.of("   "))
    }

    @Test
    fun `something that is not a url at all is refused`() {
        // Better caught here than as a puzzling failure from the network layer.
        assertNull(AiEndpoint.of("api.perplexity.ai"))
        assertNull(AiEndpoint.of("ftp://api.perplexity.ai"))
    }
}
