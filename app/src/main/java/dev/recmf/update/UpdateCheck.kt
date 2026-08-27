/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.update

import org.json.JSONObject

/** A build newer than this one, and where to get it. */
data class AvailableUpdate(
    val versionCode: Int,
    val name: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Reads GitHub's "latest release" answer.
 *
 * Kept apart from the fetching so it can be tested without a network: this is the part
 * that decides whether a phone downloads and installs something, and getting it wrong is
 * worse than not having it.
 */
object UpdateCheck {

    const val LATEST_RELEASE_URL: String =
        "https://api.github.com/repos/Bendy3192/reCMF/releases/latest"

    /**
     * @param currentVersionCode the running build's number.
     * @return the update, or null when the latest release is this build or older.
     */
    fun parse(json: String, currentVersionCode: Int): AvailableUpdate? {
        val release = JSONObject(json)

        // The tag is the version: CI names it build-<run number>, and versionCode is that
        // same number. Comparing tags means the check costs one small request rather than
        // a download.
        val tag = release.optString("tag_name")
        val version = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return null
        if (version <= currentVersionCode) return null

        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null

        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk")) continue

            val url = asset.optString("browser_download_url").ifBlank { continue }

            return AvailableUpdate(
                versionCode = version,
                name = release.optString("name").ifBlank { tag },
                apkUrl = url,
                sizeBytes = asset.optLong("size"),
            )
        }

        // A release with no APK is a release nothing can be installed from, which is not
        // an update however new its tag is.
        return null
    }

    private const val TAG_PREFIX = "build-"
}
