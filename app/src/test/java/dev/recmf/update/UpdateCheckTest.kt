package dev.recmf.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckTest {

    private fun redirect(tag: String) =
        "https://github.com/${UpdateCheck.REPOSITORY}/releases/tag/$tag"

    @Test
    fun `the tag is read out of the redirect`() {
        assertEquals("build-58", UpdateCheck.tagOf(redirect("build-58")))
    }

    @Test
    fun `a query or fragment is not part of the tag`() {
        assertEquals("build-58", UpdateCheck.tagOf(redirect("build-58") + "?utm=x"))
        assertEquals("build-58", UpdateCheck.tagOf(redirect("build-58") + "#notes"))
    }

    @Test
    fun `a redirect to the releases listing names no release`() {
        // What a repository with no releases at all answers with.
        assertNull(UpdateCheck.tagOf("https://github.com/${UpdateCheck.REPOSITORY}/releases"))
    }

    @Test
    fun `a redirect somewhere else entirely names no release`() {
        assertNull(UpdateCheck.tagOf("https://github.com/login?return_to=%2Fsomewhere"))
    }

    @Test
    fun `a tag that could steer the download URL is refused`() {
        // The tag is pasted into the download URL, so this is the one that matters.
        assertNull(UpdateCheck.tagOf(redirect("../../../etc/passwd")))
        assertNull(UpdateCheck.tagOf(redirect("build 58")))
        assertNull(UpdateCheck.tagOf(redirect("build%2f58")))
    }

    @Test
    fun `a newer build is offered`() {
        assertEquals(
            AvailableUpdate(
                versionCode = 58,
                name = "build 58",
                apkUrl = "https://github.com/${UpdateCheck.REPOSITORY}" +
                    "/releases/download/build-58/${UpdateCheck.APK_NAME}",
                notesUrl = "https://github.com/${UpdateCheck.REPOSITORY}" +
                    "/releases/download/build-58/${UpdateCheck.NOTES_NAME}",
                changelogUrl = "https://github.com/${UpdateCheck.REPOSITORY}" +
                    "/releases/download/build-58/${UpdateCheck.CHANGELOG_NAME}",
            ),
            UpdateCheck.update("build-58", currentVersionCode = 57),
        )
    }

    @Test
    fun `the changelog keeps the changes and drops the rest`() {
        // Exactly the shape the workflow writes: bullets, then a comparison link and the
        // commit, both of which are for someone at a keyboard.
        val body = """
            - Ring the phone when the watch asks for it
            - Read the watch's own settings, and show them

            [Everything that changed](https://github.com/o/r/compare/build-1...build-2)

            Built from `abc1234`.
        """.trimIndent()

        assertEquals(
            "• Ring the phone when the watch asks for it\n" +
                "• Read the watch's own settings, and show them",
            UpdateCheck.readableNotes(body),
        )
    }

    /** The shape the workflow writes: newest release first, each under its own heading. */
    private fun changelog(vararg releases: Pair<Int, List<String>>): String =
        releases.joinToString("\n") { (version, entries) ->
            "## build $version\n" + entries.joinToString("\n") { "- $it" } + "\n"
        }

    @Test
    fun `a phone several builds behind is told about all of them`() {
        // The reason this exists. Someone who updates every build already knows what
        // changed; someone who opens the app after twenty builds was being shown the last
        // one and no sign of the other nineteen.
        val file = changelog(
            140 to listOf("Put each card's colour on the card it describes"),
            139 to listOf("Ask for the workouts instead of waiting for them"),
            138 to listOf("Draw the exercises", "Let the tabs spring"),
        )

        assertEquals(
            """
            build 140
            • Put each card's colour on the card it describes

            build 139
            • Ask for the workouts instead of waiting for them

            build 138
            • Draw the exercises
            • Let the tabs spring
            """.trimIndent(),
            UpdateCheck.notesSince(file, installed = 137),
        )
    }

    @Test
    fun `releases the phone already has are left out`() {
        val file = changelog(
            140 to listOf("newest"),
            139 to listOf("also new"),
            138 to listOf("already installed"),
            137 to listOf("older still"),
        )

        assertEquals(
            "build 140\n• newest\n\nbuild 139\n• also new",
            UpdateCheck.notesSince(file, installed = 138),
        )
    }

    @Test
    fun `one release on its own reads as it always did`() {
        // A heading above a single list says nothing the update card has not already said
        // in the line above it.
        val file = changelog(140 to listOf("Put each card's colour on the card it describes"))

        assertEquals(
            "• Put each card's colour on the card it describes",
            UpdateCheck.notesSince(file, installed = 139),
        )
    }

    @Test
    fun `a very long history is cut rather than shown whole`() {
        // Forty lines is already more than anyone reads standing at a bus stop, and the
        // release page is a tap away.
        val file = changelog(*(1..60).reversed().map { it to listOf("change $it") }.toTypedArray())

        val notes = UpdateCheck.notesSince(file, installed = 0)!!

        assertTrue(notes.endsWith("…"), "a cut history has to say it was cut")
        assertTrue(notes.startsWith("build 60"), "the newest release comes first")
        // Twenty headings and twenty changes is the forty this is allowed.
        assertEquals(20, notes.lines().count { it.startsWith("• ") })
    }

    @Test
    fun `a history that ends on the limit is not marked as cut`() {
        // Twenty releases of one change each is exactly forty lines. Saying there is more
        // sends the reader looking for something that is not there.
        val file = changelog(*(1..20).reversed().map { it to listOf("change $it") }.toTypedArray())

        assertTrue(UpdateCheck.notesSince(file, installed = 0)!!.endsWith("change 1"))
    }

    @Test
    fun `nothing newer means nothing to say, so the single release notes can be tried`() {
        val file = changelog(140 to listOf("newest"))

        assertNull(UpdateCheck.notesSince(file, installed = 140))
        assertNull(UpdateCheck.notesSince(file, installed = 200))
        // Not one of ours at all, which is what a 404 page saved to a file looks like.
        assertNull(UpdateCheck.notesSince("<html>Not Found</html>", installed = 1))
        assertNull(UpdateCheck.notesSince("", installed = 1))
    }

    @Test
    fun `anything before the first heading belongs to no release`() {
        // A preamble would otherwise be attributed to whichever release came first.
        val file = "- a line with no release above it\n" + changelog(140 to listOf("real"))

        assertEquals("• real", UpdateCheck.notesSince(file, installed = 139))
    }

    @Test
    fun `a release with nothing to say has no notes rather than empty ones`() {
        assertNull(UpdateCheck.readableNotes("Built from `abc1234`."))
        assertNull(UpdateCheck.readableNotes(""))
    }

    @Test
    fun `the build already running is not an update`() {
        assertNull(UpdateCheck.update("build-57", currentVersionCode = 57))
    }

    @Test
    fun `an older release is not an update`() {
        // Re-running an old workflow can move the latest tag backwards; installing that
        // would be a downgrade, which Android refuses anyway.
        assertNull(UpdateCheck.update("build-12", currentVersionCode = 57))
    }

    @Test
    fun `a tag that is not a build number is not guessed at`() {
        assertNull(UpdateCheck.update("v1.0", currentVersionCode = 57))
        assertNull(UpdateCheck.update("nightly", currentVersionCode = 57))
    }

    @Test
    fun `a tag that is only a number is not one of ours`() {
        // removePrefix leaves a tag without the prefix untouched, so this would otherwise
        // parse as version 999 and offer an update from a release nobody built here.
        assertNull(UpdateCheck.update("999", currentVersionCode = 57))
    }
}
