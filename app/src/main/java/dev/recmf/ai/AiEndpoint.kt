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

    /** The path every OpenAI-shaped API answers on. */
    const val PATH = "/chat/completions"

    /**
     * @return the full URL to post to, or null when there is nothing usable to build from.
     *   A blank field is not an error worth a message; it is a setting nobody has filled in
     *   yet, and the caller says so in its own words.
     */
    fun of(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null

        // Already the endpoint: taking the person at their word rather than appending a
        // second copy of the path onto it.
        return if (trimmed.endsWith(PATH)) trimmed else trimmed + PATH
    }
}
