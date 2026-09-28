// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.bockelie.bebird.ui.ViewerScreen
import com.bockelie.bebird.ui.ViewerViewModel

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ViewerScreen(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app sends STOP and drops the scope's network; a rotation keeps both.
        if (!isChangingConfigurations) vm.connection.disconnect()
    }
}
