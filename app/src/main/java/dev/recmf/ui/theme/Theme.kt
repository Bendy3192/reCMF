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
 * wavy progress indicators — is not used here because it cannot be. Those APIs are public
 * from the 1.5.0 line, and 1.5.0-alpha27 was tried: it fails at `checkDebugAarMetadata`,
 * because `material3-ripple` requires compileSdk 37 and AGP 9.1, and the line pulls
 * `compose.animation` 1.12.0-beta01, which requires the same. The Android 17 platform is
 * not published to any installable SDK channel yet.
 *
 * So the blocker is one thing, not two: the platform. When it ships, this file and two
 * call sites change, and everything drawn by hand below can be handed back to the library.
 *
 * The obvious escape was tried and does not exist. Compose libraries routinely ship an
 * API a release early behind an opt-in annotation, so 1.4.0 — the newest stable there is —
 * ought to have carried Expressive under `ExperimentalMaterial3ExpressiveApi` with nothing
 * owed but an `@OptIn`. It carries it, and the annotation is real; the compiler resolves
 * every name and prints the full signature back:
 *
 * ```
 * Cannot access 'fun MaterialExpressiveTheme(colorScheme: ColorScheme? = ...,
 *   motionScheme: MotionScheme? = ..., shapes: Shapes? = ..., typography: Typography? = ...,
 *   content: ComposableFunction0<Unit>): Unit': it is internal in file.
 * Cannot access 'interface MotionScheme : Any': it is internal in file.
 * Cannot access 'annotation class ExperimentalMaterial3ExpressiveApi': it is internal in file.
 * ```
 *
 * `internal`, not experimental. The whole surface is compiled into the artifact and sealed
 * inside it, which is precisely what "public from the 1.5.0 line" means and why no opt-in
 * gets past it. The wavy indicators are not even that far along: `LinearWavyProgressIndicator`
 * does not resolve at all in 1.4.0.
 *
 * Which leaves nothing to take early. The platform really is the only door.
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
