/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.update

/**
 * A build newer than this one, and where to get it.
 *
 * @param notes what changed, as the release says, or null when it could not be read. Not
 *   worth failing an update over: a version the wearer cannot describe is still a version
 *   they can install.
 */
data class AvailableUpdate(
    val versionCode: Int,
    val name: String,
    val apkUrl: String,
    val notesUrl: String,
    val notes: String? = null,
)

/**
 * Works out whether GitHub is offering a newer build.
 *
 * This deliberately does **not** use GitHub's API. The API allows an unauthenticated
 * client sixty requests an hour *per IP address*, and a phone on mobile data shares one
 * carrier address with thousands of strangers — so the quota is routinely spent by people
 * the wearer has never met, and the check comes back 403 no matter how rarely they press
 * the button. Sending a token instead would mean shipping a credential inside a public
 * app, which is worse.
 *
 * What is used instead is the ordinary release page. `/releases/latest` answers any
 * browser with a redirect to the newest release's own URL, which ends in its tag — and
 * since CI names the tag after the version code, the tag is the whole answer. No quota
 * applies, nothing needs authenticating, and the reply is a header rather than a document.
 *
 * The cost is that the download URL is constructed rather than read from a listing. That
 * holds as long as the workflow keeps uploading the asset under [APK_NAME]; if it ever
 * does not, the download fails with a 404 that says so, which is a better failure than a
 * check nobody can complete.
 *
 * Kept apart from the fetching so it can be tested without a network: this is the part
 * that decides whether a phone installs something, and getting it wrong is worse than not
 * having it.
 */
object UpdateCheck {

    /**
     * The repository releases are taken from.
     *
     * Forks that build their own APKs should change this — a fork's app otherwise offers
     * its users the upstream build, which is signed with a different key and will not
     * install over theirs.
     */
    const val REPOSITORY: String = "Bendy3192/reCMF"

    /** The asset name the workflow uploads. */
    const val APK_NAME: String = "recmf.apk"

    /**
     * The changelog, uploaded as an asset next to the APK.
     *
     * The release body says the same thing, but reading that means asking the API, and
     * the API is the thing this file exists to avoid. An asset comes down the same
     * quota-free path as the APK itself.
     */
    const val NOTES_NAME: String = "notes.md"

    const val LATEST_RELEASE_URL: String = "https://github.com/$REPOSITORY/releases/latest"

    /**
     * The release tag a redirect points at, or null when it points at no release.
     *
     * A repository with no releases at all redirects to the releases listing instead of to
     * a release, which is why this can come back empty-handed on a perfectly good reply.
     */
    fun tagOf(location: String): String? {
        val marker = "/releases/tag/"
        if (!location.contains(marker)) return null

        val tag = location.substringAfter(marker)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')

        // The tag is pasted into the download URL, so anything that could steer that URL
        // somewhere else — a slash, a dot pair, an escape — is refused rather than
        // cleaned up. Git tags are this alphabet in practice; a tag that is not gets no
        // update offered, which is the safe way to be wrong.
        return tag.takeIf { it.isNotEmpty() && it.all(::allowedInTag) }
    }

    private fun allowedInTag(c: Char): Boolean =
        (c.isLetterOrDigit() && c.code < 128) || c == '.' || c == '_' || c == '-'

    /**
     * @param currentVersionCode the running build's number.
     * @return the update, or null when [tag] names this build or an older one.
     */
    fun update(tag: String, currentVersionCode: Int): AvailableUpdate? {
        // The tag is the version: CI names it build-<version code>, taken from the APK's
        // own metadata so the two cannot drift apart.
        val version = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return null

        // removePrefix leaves a tag without the prefix untouched, so a bare "205" would
        // otherwise parse as a version. A tag that is only a number is not one of ours.
        if (tag == version.toString()) return null
        if (version <= currentVersionCode) return null

        return AvailableUpdate(
            versionCode = version,
            name = tag.replace('-', ' '),
            apkUrl = downloadUrl(tag, APK_NAME),
            notesUrl = downloadUrl(tag, NOTES_NAME),
        )
    }

    private fun downloadUrl(tag: String, asset: String): String =
        "https://github.com/$REPOSITORY/releases/download/$tag/$asset"

    /**
     * Trims a changelog down to something a notification and a card can hold.
     *
     * The release body ends with a comparison link and the commit it was built from, which
     * are for someone at a keyboard, not someone deciding whether to tap Install.
     */
    fun readableNotes(body: String): String? = body
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ") }
        .take(MAX_NOTE_LINES)
        .joinToString("\n") { "• $it" }
        .takeIf { it.isNotBlank() }

    /** Longer than this is not a summary, and no notification shows it anyway. */
    private const val MAX_NOTE_LINES = 8

    private const val TAG_PREFIX = "build-"
}
