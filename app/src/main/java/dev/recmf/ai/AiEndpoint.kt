/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ai

/**
 * Turning what somebody typed into a URL to post to.
 *
 * Its own file, and no JSON in it, so it can be tested where the rest of the client
 * cannot: this is the part fed directly by a text field, which makes it the part most
 * likely to be wrong, and `org.json` is not fetchable in the environment this is written
 * in.
 *
 * People paste what their provider's documentation showed them, and that is not one
 * shape. Some pages give the host, some give the host and a version segment, and some give
 * the whole endpoint including the path. All three are what the person meant, so all three
 * work rather than one being correct and the others being a mistake they have to find.
 */
object AiEndpoint {

    /**
     * The two request shapes worth speaking, because between them they cover everything.
     *
     * [CHAT] is the older chat-completions format that OpenAI, OpenRouter, Groq, and
     * anything running locally answer, and it is still the right default.
     *
     * [RESPONSES] is OpenAI's newer Responses shape. It matters here because Perplexity's
     * Agent API speaks it and their Sonar endpoints — which speak the other one — retire on
     * 27 September 2026. An app that only spoke chat-completions would simply stop working
     * against Perplexity on that date.
     */
    enum class Wire(val path: String) {
        CHAT("/chat/completions"),
        RESPONSES("/responses"),
    }

    /** Where an OpenAI-shaped API lists what it will answer for. */
    private const val MODELS_PATH = "/models"

    /**
     * @return the full URL to post to, or null when there is nothing usable to build from.
     *   A blank field is not an error worth a message; it is a setting nobody has filled in
     *   yet, and the caller says so in its own words.
     */
    fun of(baseUrl: String, wire: Wire = Wire.CHAT): String? {
        val root = root(baseUrl) ?: return null
        return root + wire.path
    }

    /**
     * The address with any endpoint path taken back off it.
     *
     * Both paths are stripped rather than only the one being asked for, so somebody who
     * pasted a completions URL and then switched the shape to Responses gets the right
     * address instead of `/chat/completions/responses`.
     */
    private fun root(baseUrl: String): String? {
        var trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null

        Wire.entries.forEach { trimmed = trimmed.removeSuffix(it.path) }
        // Perplexity's Agent endpoint under its own name, which is an alias for /responses.
        trimmed = trimmed.removeSuffix("/agent")

        return trimmed.trimEnd('/')
    }

    /**
     * Where to ask what models this provider has.
     *
     * Built from the same field, so somebody who pasted the whole completions endpoint gets
     * a working list rather than a puzzling 404: the path is taken off again before this
     * one is put on.
     *
     * Not every provider serves it. That is a fine answer and the caller says so — the
     * point of offering a list is to save typing, not to become a requirement.
     */
    fun models(baseUrl: String): String? = root(baseUrl)?.plus(MODELS_PATH)
}
