/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 with the wallpaper palette.
 *
 * Dynamic colour is unconditional: the minimum SDK is already above the version that
 * introduced it, so there is no static fallback palette to keep in sync.
 *
 * Material 3 Expressive — `MaterialExpressiveTheme`, `MotionScheme.expressive()`, the
 * wavy progress indicators — is deliberately not used here. Those APIs are still
 * `internal` in material3 1.4.0 and only become public in the 1.5.0 line, which pulls
 * Compose 1.12 and so requires compileSdk 37; the Android 17 platform is not published
 * to any installable SDK channel yet. See gradle/libs.versions.toml. Switching over is a
 * change to this file and two call sites once that lands.
 */
@Composable
fun ReCmfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
