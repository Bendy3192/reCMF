package dev.recmf.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UpdateCheckTest {

    private fun release(
        tag: String = "build-58",
        name: String = "build 58",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """[{"name":"recmf.apk","browser_download_url":"https://example/recmf.apk","size":1234}]""",
    ) = """{"tag_name":"$tag","name":"$name","draft":$draft,"prerelease":$prerelease,"assets":$assets}"""

    @Test
    fun `a newer build is offered`() {
        val update = UpdateCheck.parse(release(), currentVersionCode = 57)

        assertEquals(
            AvailableUpdate(58, "build 58", "https://example/recmf.apk", 1234),
            update,
        )
    }

    @Test
    fun `the build already running is not an update`() {
        assertNull(UpdateCheck.parse(release(tag = "build-57"), currentVersionCode = 57))
    }

    @Test
    fun `an older release is not an update`() {
        // Re-running an old workflow can move the latest tag backwards; installing that
        // would be a downgrade, which Android refuses anyway.
        assertNull(UpdateCheck.parse(release(tag = "build-12"), currentVersionCode = 57))
    }

    @Test
    fun `a release with no apk is not an update`() {
        assertNull(UpdateCheck.parse(release(assets = "[]"), currentVersionCode = 57))
    }

    @Test
    fun `a release whose only asset is not an apk is not an update`() {
        val notApk = """[{"name":"mapping.txt","browser_download_url":"https://example/m.txt","size":9}]"""

        assertNull(UpdateCheck.parse(release(assets = notApk), currentVersionCode = 57))
    }

    @Test
    fun `drafts and prereleases are ignored`() {
        assertNull(UpdateCheck.parse(release(draft = true), currentVersionCode = 57))
        assertNull(UpdateCheck.parse(release(prerelease = true), currentVersionCode = 57))
    }

    @Test
    fun `a tag that is not a build number is not guessed at`() {
        assertNull(UpdateCheck.parse(release(tag = "v1.0"), currentVersionCode = 57))
    }
}
