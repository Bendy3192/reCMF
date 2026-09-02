/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive with the wallpaper palette.
 *
 * Dynamic colour is unconditional: the minimum SDK is already above the version that
 * introduced it, so there is no static fallback palette to keep in sync.
 *
 * Expressive was written off here once, and the note was wrong in a way worth recording,
 * because it is the mistake the version numbers invite. `material3` 1.5.0-alpha27 really
 * was tried and really does fail — `material3-ripple` wants compileSdk 37 and the line
 * drags in `compose.animation` 1.12.0, which wants the same, and the Android 17 platform
 * is still not published to any installable SDK channel. All true. The wrong part was the
 * conclusion drawn from it: that Expressive therefore could not be used at all.
 *
 * 1.5.0 is where these APIs go *stable*. 1.4.0 — the newest stable release, the one
 * already pinned — is where they *arrived*, behind [ExperimentalMaterial3ExpressiveApi].
 * Opting in is the whole of the cost. Nothing here needs the unbuildable line.
 *
 * What that buys is mostly motion. [MotionScheme] is read by the components themselves,
 * so a single scheme on the theme changes how everything below springs and settles rather
 * than sliding on a curve — one declaration instead of a hand-tuned animation per widget.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    // Spelled out rather than left to the default, because it is the point of the change.
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
