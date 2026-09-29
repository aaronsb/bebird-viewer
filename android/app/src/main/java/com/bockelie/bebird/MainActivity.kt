// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.ui.BebirdTheme
import com.bockelie.bebird.ui.isDark
import com.bockelie.bebird.ui.ViewerScreen
import com.bockelie.bebird.ui.ViewerViewModel
import com.bockelie.bebird.ui.wifiPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()
    private val grace get() = (application as BebirdApp).grace
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launch, not a rotation or a return: connect to the last device. Without the Wi-Fi
        // permission there is nothing to do yet; Connect asks for it.
        val permitted = ContextCompat.checkSelfPermission(this, wifiPermission) == PackageManager.PERMISSION_GRANTED
        if (savedInstanceState == null && permitted) vm.connection.connectOnLaunch()
        askForNotificationsOnFirstVideo()
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
        var askedNotifications = false  // in this process

        // The navigation bar scrims enableEdgeToEdge() uses by default (3-button navigation).
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }

    override fun onStart() {
        super.onStart()
        grace.onReturn()
    }

    override fun onResume() {
        super.onResume()
        grace.onResumed()
    }

    override fun onStop() {
        super.onStop()
        // A rotation keeps the connection. Otherwise recording stops and its file is finished
        // first (recording is foreground-only), then the connection is kept for the grace period
        // (#18; at least a while for our own Files/Open launch), or ended now, powering the scope
        // off if that is set, when the app is closing (finishing) or the period is Immediately.
        if (isChangingConfigurations) return
        vm.stopRecording()
        if (isFinishing) grace.onClose() else grace.onLeave()
    }

    /**
     * The grace period's countdown is a notification, which needs permission on Android 13+:
     * asked once video first starts in this run, when keeping the connection is set, so the
     * request comes with something to explain it. Without it the service still runs.
     */
    private fun askForNotificationsOnFirstVideo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedNotifications) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        lifecycleScope.launch {
            vm.connection.isStreaming.first { it }
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted && vm.connectionSettings.value.graceSeconds > 0 && !askedNotifications) {
                askedNotifications = true  // not again after a rotation
                askNotifications.launch(permission)
            }
        }
    }
}
