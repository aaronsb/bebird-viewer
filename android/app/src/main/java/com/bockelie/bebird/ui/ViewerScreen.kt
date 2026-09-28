// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.R
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.devices.KnownDevice
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.wifi.ScopeWifi

/** Joining a network by specifier needs this permission: nearby devices on 13+, location before. */
private val wifiPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

@Composable
fun ViewerScreen(vm: ViewerViewModel) {
    val conn = vm.connection
    val wifi by conn.wifiState.collectAsStateWithLifecycle()
    val stats by conn.stats.collectAsStateWithLifecycle()
    val book by conn.book.collectAsStateWithLifecycle()
    val inRange by conn.inRange.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val idle = wifi == ScopeWifi.State.Idle || wifi == ScopeWifi.State.Unavailable || wifi is ScopeWifi.State.Failed

    // Every way of joining needs the permission first; the pending action runs once it's granted.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    fun withPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, wifiPermission) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            permission.launch(wifiPermission)
        }
    }

    var renaming by remember { mutableStateOf<KnownDevice?>(null) }
    LaunchedEffect(idle) { if (idle) conn.refreshInRange() }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val last = book.last
            Button(onClick = { if (idle) withPermission(conn::connect) else conn.disconnect() }) {
                Text(
                    when {
                        !idle -> stringResource(R.string.disconnect)
                        last != null -> stringResource(R.string.connect_to, last.label)
                        else -> stringResource(R.string.connect)
                    }
                )
            }
            TextButton(onClick = { withPermission(conn::pickDifferent) }) {
                Text(stringResource(R.string.pick_different))
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
            Devices(conn, book.sorted, inRange, onRename = { renaming = it }, withPermission = ::withPermission)
        }
    }

    renaming?.let { device -> RenameDialog(device, onDone = { name -> conn.rename(device, name); renaming = null }, onCancel = { renaming = null }) }
}

@Composable
private fun Devices(
    conn: ScopeConnection,
    devices: List<KnownDevice>,
    inRange: List<ScopeWifi.Identity>,
    onRename: (KnownDevice) -> Unit,
    withPermission: (() -> Unit) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.known_devices), style = MaterialTheme.typography.titleSmall)
        if (devices.isEmpty()) Text(stringResource(R.string.no_known_devices), style = MaterialTheme.typography.bodySmall)
        for (d in devices) {
            HorizontalDivider()
            val near = inRange.any { d.matches(it.ssid, it.bssid) }
            Text(d.label, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(
                    d.ssid.takeIf { d.nickname != null },
                    d.bssid ?: stringResource(R.string.bssid_unknown),
                    DateUtils.getRelativeTimeSpanString(d.lastSeen).toString(),
                    stringResource(R.string.in_range).takeIf { near },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { withPermission { conn.connectTo(d.ssid, d.bssid) } }) { Text(stringResource(R.string.connect)) }
                TextButton(onClick = { onRename(d) }) { Text(stringResource(R.string.rename)) }
                TextButton(onClick = { conn.forget(d) }) { Text(stringResource(R.string.forget)) }
            }
        }
        // Scopes in the last scan that we haven't joined yet; Android asks once for each.
        val unknown = inRange.filter { s -> s.ssid != null && devices.none { it.matches(s.ssid, s.bssid) } }
        for (s in unknown) {
            HorizontalDivider()
            Text(s.ssid!!, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(s.bssid, stringResource(R.string.in_range_new)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { withPermission { conn.connectTo(s.ssid, s.bssid) } }) { Text(stringResource(R.string.connect)) }
        }
    }
}

@Composable
private fun RenameDialog(device: KnownDevice, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(device.nickname.orEmpty()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.rename_title, device.ssid)) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(device.ssid) })
        },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun statusLine(wifi: ScopeWifi.State, s: ScopeSession.Stats): String {
    val net = when (wifi) {
        ScopeWifi.State.Idle -> "Wi-Fi idle"
        ScopeWifi.State.Requesting -> "joining scope Wi-Fi…"
        is ScopeWifi.State.Available -> "on scope Wi-Fi"
        ScopeWifi.State.Lost -> "scope Wi-Fi lost"
        ScopeWifi.State.Unavailable -> "no scope network (cancelled or not found)"
        is ScopeWifi.State.Failed -> "could not request the network: ${wifi.reason}"
    }
    val battery = s.battery?.let { "${it.percent}% (${it.stateName})" } ?: "–"
    return "$net · ${s.status}\n${s.fps} fps · battery $battery · " +
        "frames ${s.frames}, dropped ${s.dropped}, bad ${s.undecodable} · roll ${s.angle}°"
}
