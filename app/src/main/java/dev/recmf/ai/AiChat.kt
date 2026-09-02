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
     * One request.
     *
     * No temperature, no penalties, no sampling settings. They would be four more fields on
     * a settings screen, they differ in meaning between providers, and none of them changes
     * whether the thing said about somebody's resting pulse is true.
     */
    fun body(model: String, system: String, user: String): String = JSONObject()
        .put("model", model)
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)),
        )
        .toString()

    /**
     * The assistant's words, or null when the reply carried none.
     *
     * @return the message content, trimmed. Null covers both a malformed reply and a
     *   well-formed one with an empty answer in it, because they mean the same thing to
     *   somebody looking at the screen.
     */
    fun reply(json: String): String? = try {
        JSONObject(json)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
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
