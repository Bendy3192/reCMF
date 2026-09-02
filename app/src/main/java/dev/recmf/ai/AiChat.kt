/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The OpenAI chat-completions shape, which is the only wire format reCMF speaks.
 *
 * Not a provider, a shape. Perplexity, OpenAI, OpenRouter, Groq and anything serving a
 * model on the same desk all answer this, so pointing the app somewhere else is three text
 * fields rather than a rewrite. That is the whole reason for choosing it.
 *
 * Reading is deliberately forgiving in one direction and strict in the other: a reply that
 * arrives in a shape not quite expected is worth digging a message out of, but a reply
 * with no text in it is reported as unreadable rather than shown as an empty answer.
 */
object AiChat {

    /**
     * A ceiling on the answer, generous enough never to be the thing that cuts one short.
     *
     * The prompt asks for a few sentences and the card shows a paragraph, so this is far
     * above anything expected — it is here to satisfy providers that demand a number, not
     * to shape the reply.
     */
    private const val MAX_OUTPUT_TOKENS = 2000

    /**
     * One request.
     *
     * No temperature, no penalties, no sampling settings. They would be four more fields on
     * a settings screen, they differ in meaning between providers, and none of them changes
     * whether the thing said about somebody's resting pulse is true.
     */
    /**
     * One thing said, by one side.
     *
     * A chat endpoint keeps nothing between calls, so a conversation is not something the
     * provider remembers — it is something reCMF resends in full every time. A turn is
     * therefore the unit of both the screen and the request, and the same list serves both.
     */
    data class Turn(val fromUser: Boolean, val text: String) {
        val role: String get() = if (fromUser) "user" else "assistant"
    }

    fun body(
        model: String,
        system: String,
        turns: List<Turn>,
        wire: AiEndpoint.Wire = AiEndpoint.Wire.CHAT,
        webSearch: Boolean = false,
    ): String = when (wire) {
        AiEndpoint.Wire.CHAT -> JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .apply {
                        turns.forEach {
                            put(JSONObject().put("role", it.role).put("content", it.text))
                        }
                    },
            )
            .toString()

        // The Responses shape: the standing instruction is a field of its own rather than
        // a message with a role, and what is being asked is `input` — which takes either a
        // bare string or the same list of roles the other shape uses. The list is sent
        // either way, because a conversation of one is still a conversation and two code
        // paths for that would be one to forget.
        AiEndpoint.Wire.RESPONSES -> JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put(
                "input",
                JSONArray().apply {
                    turns.forEach {
                        put(JSONObject().put("role", it.role).put("content", it.text))
                    }
                },
            )
            // Required, not optional, for some of what the Agent API hosts: Anthropic
            // models there refuse the request outright without it. Sent always rather than
            // guessed at per model, since a cap the answer never reaches costs nothing and
            // a missing one costs the whole request.
            .put("max_output_tokens", MAX_OUTPUT_TOKENS)
            .apply {
                // Search is a tool here, not something the model does of its own accord.
                // Asked for by name or it simply does not happen — which is the whole
                // reason somebody would be pointing this at Perplexity in the first place.
                if (webSearch) {
                    put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                }
            }
            .toString()
    }

    /**
     * The assistant's words, or null when the reply carried none.
     *
     * @return the message content, trimmed. Null covers both a malformed reply and a
     *   well-formed one with an empty answer in it, because they mean the same thing to
     *   somebody looking at the screen.
     */
    fun reply(json: String): String? = try {
        val root = JSONObject(json)

        // Every place either shape is known to put the answer, tried in turn. Reading is
        // deliberately looser than writing: a reply that arrives in a shape not quite
        // expected is still worth an answer, and the alternative — a parser that only
        // knows the one endpoint it was written against — is what makes an app like this
        // break when a provider moves.
        chatReply(root) ?: responsesReply(root)
    } catch (e: JSONException) {
        null
    }

    /**
     * The ids out of a `/models` reply, in whichever of the two usual shapes it came.
     *
     * Sorted, because a provider's own order is not one anybody is looking for, and a list
     * somebody has to scan for a name is easier to scan alphabetically.
     */
    fun models(json: String): List<String> = try {
        val root = JSONObject(json)
        val list = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()

        (0 until list.length()).mapNotNull { at ->
            when (val entry = list.opt(at)) {
                is JSONObject -> entry.optString("id").takeIf { it.isNotEmpty() }
                    ?: entry.optString("name").takeIf { it.isNotEmpty() }
                is String -> entry.takeIf { it.isNotEmpty() }
                else -> null
            }
        }.sorted()
    } catch (e: JSONException) {
        emptyList()
    }

    /**
     * Where an answer said it had been reading, when it says.
     *
     * Providers put this in more than one place and under more than one name, so all the
     * usual ones are tried. It is often empty and that is not a fault: Perplexity returns
     * citations for its own Sonar models and not for third-party models run through the
     * Agent API, so an answer from Claude or GPT there is search-grounded without being
     * able to show its working.
     */
    fun sources(json: String): List<String> = try {
        val root = JSONObject(json)
        val list = root.optJSONArray("citations")
            ?: root.optJSONArray("search_results")
            ?: JSONArray()

        (0 until list.length()).mapNotNull { at ->
            when (val entry = list.opt(at)) {
                is String -> entry.takeIf { it.startsWith("http") }
                is JSONObject -> entry.optString("url").takeIf { it.startsWith("http") }
                else -> null
            }
        }.distinct()
    } catch (e: JSONException) {
        emptyList()
    }

    /** `choices[0].message.content`, which is the chat-completions shape. */
    private fun chatReply(root: JSONObject): String? = root
        .optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * The Responses shape, which nests further and offers a shortcut.
     *
     * `output_text` is the convenience field; where it is absent the text lives inside
     * `output[]`, whose entries carry a `content[]` of their own. Both are walked, and
     * everything found is joined — a reply split across several parts is one answer.
     */
    private fun responsesReply(root: JSONObject): String? {
        root.optString("output_text").trim().takeIf { it.isNotEmpty() }?.let { return it }

        val output = root.optJSONArray("output") ?: return null
        val text = buildList {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    content.optJSONObject(j)?.optString("text")?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.joinToString("\n").trim()

        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * What a provider said went wrong, when it bothered to say.
     *
     * Providers disagree about the shape of this — some nest a message under `error`, some
     * put a bare string there — so both are tried before giving up. The message is worth
     * the trouble: "insufficient credit" and "no such model" are both fixable by the person
     * reading it, and an HTTP status alone tells them neither.
     */
    fun error(json: String): String? = try {
        val root = JSONObject(json)
        val error = root.opt("error")

        when (error) {
            is JSONObject -> error.optString("message").takeIf { it.isNotEmpty() }
                ?: error.optString("type").takeIf { it.isNotEmpty() }
            is String -> error.takeIf { it.isNotEmpty() }
            else -> root.optString("message").takeIf { it.isNotEmpty() }
        }
    } catch (e: JSONException) {
        null
    }
}
