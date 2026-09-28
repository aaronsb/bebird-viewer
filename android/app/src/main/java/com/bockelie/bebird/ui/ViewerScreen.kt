// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.R
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.wifi.ScopeWifi

/** Joining a network by specifier needs this permission: nearby devices on 13+, location before. */
private val wifiPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

@Composable
fun ViewerScreen(vm: ViewerViewModel) {
    val wifi by vm.wifiState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.connect()
    }
    val idle = wifi == ScopeWifi.State.Idle || wifi == ScopeWifi.State.Unavailable

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = {
                when {
                    !idle -> vm.disconnect()
                    ContextCompat.checkSelfPermission(context, wifiPermission) == PackageManager.PERMISSION_GRANTED -> vm.connect()
                    else -> permission.launch(wifiPermission)
                }
            }) {
                Text(stringResource(if (idle) R.string.connect else R.string.disconnect))
            }
            Text(statusLine(wifi, stats), style = MaterialTheme.typography.bodyMedium)
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                stats.frame?.let { frame ->
                    // Rotated clockwise by the roll angle keeps the picture upright.
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().rotate(stats.angle.toFloat()),
                    )
                }
            }
        }
    }
}

private fun statusLine(wifi: ScopeWifi.State, s: ScopeSession.Stats): String {
    val net = when (wifi) {
        ScopeWifi.State.Idle -> "Wi-Fi idle"
        ScopeWifi.State.Requesting -> "joining scope Wi-Fi…"
        is ScopeWifi.State.Available -> "on scope Wi-Fi"
        ScopeWifi.State.Lost -> "scope Wi-Fi lost"
        ScopeWifi.State.Unavailable -> "no scope network (cancelled or not found)"
    }
    val battery = s.battery?.let { "${it.percent}% (${it.stateName})" } ?: "–"
    return "$net · ${s.status}\n${s.fps} fps · battery $battery · " +
        "frames ${s.frames}, dropped ${s.dropped}, bad ${s.undecodable} · roll ${s.angle}°"
}
