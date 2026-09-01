/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.update

/**
 * A build newer than this one, and where to get it.
 *
 * @param notesUrl what this one release changed.
 * @param changelogUrl every release's notes in one file, so a phone that has been left
 *   alone for twenty builds can be told what all twenty of them did rather than what the
 *   last one did.
 * @param notes what changed, as the release says, or null when it could not be read. Not
 *   worth failing an update over: a version the wearer cannot describe is still a version
 *   they can install.
 */
data class AvailableUpdate(
    val versionCode: Int,
    val name: String,
    val apkUrl: String,
    val notesUrl: String,
    val changelogUrl: String,
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

    /**
     * Every release's notes, newest first, republished whole on each release.
     *
     * [NOTES_NAME] covers one release, which is the wrong span for the person most likely
     * to be reading it. Someone who updates every build already knows what changed; the
     * one who opens the app after two months sees a single line about a colour and no sign
     * of the two months. So the whole thing ships too, and the app shows the part of it
     * that is newer than what the phone is running.
     *
     * It is a separate asset rather than a longer [NOTES_NAME] because the release body is
     * [NOTES_NAME], and a release page repeating its own history every time would bury
     * what that release actually did.
     */
    const val CHANGELOG_NAME: String = "changelog.md"

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
            changelogUrl = downloadUrl(tag, CHANGELOG_NAME),
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

    /**
     * The part of the whole changelog that is news to a phone running [installed].
     *
     * The file is sections headed `## build <version code>`, newest first, each holding
     * the commit subjects of that release. Everything at or below [installed] is dropped,
     * which is the entire point: a phone twenty builds behind is told about twenty builds
     * rather than about the last one.
     *
     * Several releases are kept apart under their own headings rather than run together.
     * Twenty unattributed bullets read as one enormous release; the headings say how long
     * the phone has been away, which is usually the more interesting fact.
     *
     * Long histories are cut at [MAX_CHANGELOG_LINES] with an ellipsis, because past a
     * screenful nobody is reading and the release page is a tap away.
     *
     * @return null when nothing in the file is newer, which includes a file that is not
     *   one of ours — a caller with a single release's notes to fall back on should use
     *   them.
     */
    fun notesSince(changelog: String, installed: Int): String? {
        val newer = sections(changelog)
            .filter { (version, entries) -> version > installed && entries.isNotEmpty() }
            .sortedByDescending { (version, _) -> version }
        if (newer.isEmpty()) return null

        val out = StringBuilder()
        var lines = 0
        var cut = false

        for ((version, entries) in newer) {
            if (cut) break

            // One release is what this looked like before there was a whole file to read,
            // and a lone heading over a lone list says nothing the card has not already.
            if (newer.size > 1) {
                if (lines >= MAX_CHANGELOG_LINES) {
                    cut = true
                    break
                }
                if (out.isNotEmpty()) out.append('\n')
                out.append(TAG_PREFIX.removeSuffix("-")).append(' ').append(version).append('\n')
                lines++
            }

            for (entry in entries) {
                if (lines >= MAX_CHANGELOG_LINES) {
                    cut = true
                    break
                }
                out.append("• ").append(entry).append('\n')
                lines++
            }
        }

        // Only when something was actually left out: a history that happens to end on the
        // limit has not been cut, and an ellipsis would send the reader looking for more
        // that is not there.
        if (cut) out.append("…")

        return out.toString().trimEnd('\n').takeIf { it.isNotBlank() }
    }

    /** Every `## build N` heading with the `- ` lines under it, in the order written. */
    private fun sections(changelog: String): List<Pair<Int, List<String>>> {
        val found = mutableListOf<Pair<Int, MutableList<String>>>()

        for (raw in changelog.lineSequence()) {
            val line = raw.trim()
            val version = versionOfHeading(line)

            if (version != null) {
                found += version to mutableListOf()
            } else if (line.startsWith("- ")) {
                // Anything before the first heading belongs to no release and is dropped,
                // which is how a preamble stays out of the notes.
                found.lastOrNull()?.second?.add(line.removePrefix("- "))
            }
        }

        return found
    }

    private fun versionOfHeading(line: String): Int? =
        line.removePrefix(HEADING).takeIf { it != line }?.trim()?.toIntOrNull()

    private const val HEADING = "## build "

    /** Longer than this is not a summary, and no notification shows it anyway. */
    private const val MAX_NOTE_LINES = 8

    /** A screenful. Past this the release page says it better than a card can. */
    private const val MAX_CHANGELOG_LINES = 40

    private const val TAG_PREFIX = "build-"
}
