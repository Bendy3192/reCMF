package dev.recmf.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import dev.recmf.ai.AiEndpoint.Wire
import org.junit.jupiter.api.Test
import org.json.JSONObject

class AiChatTest {

    /** The one-turn conversation a metric card sends. */
    private fun asked(text: String) = listOf(AiChat.Turn(fromUser = true, text = text))

    @Test
    fun `a request carries the model and both messages in order`() {
        val body = JSONObject(AiChat.body("sonar", "be careful", asked("how am I?")))
        val messages = body.getJSONArray("messages")

        assertEquals("sonar", body.getString("model"))
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be careful", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("how am I?", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `a request carries nothing else`() {
        // No temperature, no penalties. They differ in meaning between providers and none
        // of them changes whether what is said about a resting pulse is true.
        val body = JSONObject(AiChat.body("sonar", "s", asked("u")))

        assertEquals(setOf("model", "messages"), body.keys().asSequence().toSet())
    }

    @Test
    fun `the responses shape carries what that shape needs`() {
        // instructions and input rather than messages, and a token ceiling — Anthropic
        // models on Perplexity's Agent API refuse the request outright without one.
        val body = JSONObject(
            AiChat.body("anthropic/claude-sonnet-5", "be careful", asked("how am I?"), Wire.RESPONSES),
        )

        assertEquals("anthropic/claude-sonnet-5", body.getString("model"))
        assertEquals("be careful", body.getString("instructions"))
        assertEquals("how am I?", body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(body.has("max_output_tokens"))
        assertTrue(body.getInt("max_output_tokens") > 0)
    }

    @Test
    fun `search is asked for by name or not at all`() {
        val without = JSONObject(AiChat.body("m", "s", asked("u"), Wire.RESPONSES, webSearch = false))
        val withSearch = JSONObject(AiChat.body("m", "s", asked("u"), Wire.RESPONSES, webSearch = true))

        assertTrue(!without.has("tools"))
        assertEquals(
            "web_search",
            withSearch.getJSONArray("tools").getJSONObject(0).getString("type"),
        )
    }

    @Test
    fun `a responses reply is read from either place it can live`() {
        val shortcut = "{\"output_text\":\"  Sixty-six is normal for you.  \"}"
        val nested = "{\"output\":[{\"type\":\"message\",\"content\":" +
            "[{\"type\":\"output_text\",\"text\":\"Sixty-six is normal.\"}]}]}"

        assertEquals("Sixty-six is normal for you.", AiChat.reply(shortcut))
        assertEquals("Sixty-six is normal.", AiChat.reply(nested))
    }

    @Test
    fun `sources are found wherever the provider put them`() {
        assertEquals(
            listOf("https://a.example", "https://b.example"),
            AiChat.sources("{\"citations\":[\"https://a.example\",\"https://b.example\"]}"),
        )
        assertEquals(
            listOf("https://c.example"),
            AiChat.sources("{\"search_results\":[{\"url\":\"https://c.example\",\"title\":\"x\"}]}"),
        )
        assertEquals(emptyList<String>(), AiChat.sources("{}"))
    }

    @Test
    fun `a model list is read and sorted`() {
        val json = "{\"data\":[{\"id\":\"xai/grok-4.6\"},{\"id\":\"perplexity/sonar\"}]}"

        assertEquals(listOf("perplexity/sonar", "xai/grok-4.6"), AiChat.models(json))
    }

    @Test
    fun `the reply is the first choice's content`() {
        val json = """
            {"choices":[{"message":{"role":"assistant","content":"  Sixty-six is normal for you.  "}}]}
        """.trimIndent()

        assertEquals("Sixty-six is normal for you.", AiChat.reply(json))
    }

    @Test
    fun `an empty answer is the same as no answer`() {
        // Both leave somebody looking at a blank card, so they are reported alike.
        assertNull(AiChat.reply("""{"choices":[{"message":{"content":"   "}}]}"""))
        assertNull(AiChat.reply("""{"choices":[]}"""))
        assertNull(AiChat.reply("{}"))
        assertNull(AiChat.reply("not json at all"))
    }

    @Test
    fun `a nested error message is found`() {
        val json = """{"error":{"message":"Insufficient credits","type":"payment_required"}}"""

        assertEquals("Insufficient credits", AiChat.error(json))
    }

    @Test
    fun `an error given as a bare string is found too`() {
        // Providers disagree about this shape, and the message is worth the trouble:
        // "no such model" is fixable by the person reading it and a 400 is not.
        assertEquals("no such model", AiChat.error("""{"error":"no such model"}"""))
    }

    @Test
    fun `a type is used when there is no message`() {
        assertEquals("rate_limit", AiChat.error("""{"error":{"type":"rate_limit"}}"""))
    }

    @Test
    fun `a conversation is sent whole, in the order it was said`() {
        // The provider keeps nothing between calls, so the history is not a convenience —
        // it is the only reason a second question knows about the first.
        val talk = listOf(
            AiChat.Turn(fromUser = true, text = "I slept badly."),
            AiChat.Turn(fromUser = false, text = "How long?"),
            AiChat.Turn(fromUser = true, text = "Five hours."),
        )

        val messages = JSONObject(AiChat.body("m", "s", talk)).getJSONArray("messages")

        assertEquals(4, messages.length(), "the standing instructions plus three turns")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Five hours.", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun `the responses shape carries the same conversation`() {
        val talk = listOf(
            AiChat.Turn(fromUser = true, text = "I slept badly."),
            AiChat.Turn(fromUser = false, text = "How long?"),
        )

        val input = JSONObject(AiChat.body("m", "s", talk, Wire.RESPONSES)).getJSONArray("input")

        assertEquals(2, input.length(), "the instructions are a field here, not a message")
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
    }

    @Test
    fun `a refusal with nothing to say yields nothing rather than noise`() {
        assertNull(AiChat.error("{}"))
        assertNull(AiChat.error("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `the whole context survives being put in a request`() {
        // Newlines and the table's spacing are the point of the payload preview; a body
        // that mangled them would send something other than what was shown.
        val user = AiContext.user("How am I?", listOf(AiContext.Day("2026-08-28", 61, 431, 38, 44, 9012)))

        val back = JSONObject(AiChat.body("m", "s", asked(user)))
            .getJSONArray("messages").getJSONObject(1).getString("content")

        assertEquals(user, back)
        assertTrue("\n" in back)
    }
}
