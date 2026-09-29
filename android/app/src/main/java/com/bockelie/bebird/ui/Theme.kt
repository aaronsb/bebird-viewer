// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.bockelie.bebird.band.ViewerPalette
import com.bockelie.bebird.settings.ThemeMode

/** The viewer's surround on screen: light in the light theme (#51). Saved files never use it. */
val LocalViewerPalette = staticCompositionLocalOf { ViewerPalette.DARK }

/** Whether [mode] means dark right now (System follows the phone). */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Material 3, light or dark per [mode], with dynamic color on 12+, and the viewer's palette to match. */
@Composable
fun BebirdTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalViewerPalette provides if (dark) ViewerPalette.DARK else ViewerPalette.LIGHT) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
