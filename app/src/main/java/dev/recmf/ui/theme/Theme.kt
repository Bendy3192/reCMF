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
 * Material 3 Expressive, wired to the wallpaper palette.
 *
 * Dynamic colour is unconditional here: the minimum SDK is already above the version
 * that introduced it, so there is no static fallback palette to keep in sync.
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

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // The expressive spring set: state changes overshoot slightly instead of easing
        // flatly, which is what makes a connection state change read as a change.
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
