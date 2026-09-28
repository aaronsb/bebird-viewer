// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.ui.BebirdTheme
import com.bockelie.bebird.ui.ViewerScreen
import com.bockelie.bebird.ui.ViewerViewModel

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            BebirdTheme(theme) {
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
