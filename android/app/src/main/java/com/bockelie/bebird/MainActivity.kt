// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.ui.BebirdTheme
import com.bockelie.bebird.ui.isDark
import com.bockelie.bebird.ui.ViewerScreen
import com.bockelie.bebird.ui.ViewerViewModel

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val dark = isDark(theme)
            // System bar icons follow the in-app theme, not only the phone's night mode.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            BebirdTheme(theme) {
                ViewerScreen(vm)
            }
        }
    }

    private companion object {
        // The navigation bar scrims enableEdgeToEdge() uses by default (3-button navigation).
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app sends STOP and drops the scope's network; a rotation keeps both.
        if (!isChangingConfigurations) vm.connection.disconnect()
    }
}
