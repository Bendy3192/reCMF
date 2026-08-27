package dev.recmf.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
