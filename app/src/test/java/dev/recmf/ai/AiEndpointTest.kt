package dev.recmf.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import dev.recmf.ai.AiEndpoint.Wire
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
    fun `the model list is asked for beside the endpoint, not inside it`() {
        assertEquals("https://api.perplexity.ai/models", AiEndpoint.models("https://api.perplexity.ai"))
        assertEquals("https://api.openai.com/v1/models", AiEndpoint.models("https://api.openai.com/v1"))
    }

    @Test
    fun `pasting the whole completions endpoint still finds the list`() {
        // Otherwise somebody who pasted what their provider's page showed them gets a 404
        // from a URL with two paths stuck together, which explains nothing.
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            AiEndpoint.models("https://openrouter.ai/api/v1/chat/completions"),
        )
    }

    @Test
    fun `the responses shape gets its own path`() {
        assertEquals(
            "https://api.perplexity.ai/v1/responses",
            AiEndpoint.of("https://api.perplexity.ai/v1", Wire.RESPONSES),
        )
    }

    @Test
    fun `switching shape does not stack one path on the other`() {
        // Somebody pastes the completions URL, then switches to Responses. Without
        // stripping both, the request would go to /chat/completions/responses.
        val pasted = "https://api.perplexity.ai/v1/chat/completions"

        assertEquals("https://api.perplexity.ai/v1/responses", AiEndpoint.of(pasted, Wire.RESPONSES))
        assertEquals("https://api.perplexity.ai/v1/chat/completions", AiEndpoint.of(pasted, Wire.CHAT))
    }

    @Test
    fun `perplexity's agent alias is understood as the same place`() {
        assertEquals(
            "https://api.perplexity.ai/v1/responses",
            AiEndpoint.of("https://api.perplexity.ai/v1/agent", Wire.RESPONSES),
        )
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
