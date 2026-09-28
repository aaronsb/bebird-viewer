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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.R
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.devices.DeviceBook
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
    val idle = wifi == ScopeWifi.State.Idle || wifi is ScopeWifi.State.Unavailable || wifi is ScopeWifi.State.Failed

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

    var choosing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<KnownDevice?>(null) }
    LaunchedEffect(idle, choosing) { if (idle || choosing) conn.refreshInRange() }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = book.last
            // The device selector: what Connect goes to, and where devices are managed.
            OutlinedButton(onClick = { choosing = true }) {
                Text(current?.label ?: stringResource(R.string.no_device))
                Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.choose_device))
            }
            Button(onClick = { if (idle) withPermission(conn::connect) else conn.disconnect() }) {
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

    if (choosing) {
        DeviceSheet(
            book = book,
            inRange = inRange,
            onDismiss = { choosing = false },
            onSelect = { choosing = false; conn.select(it) },
            onPickDifferent = { choosing = false; withPermission(conn::pickDifferent) },
            onConnectNew = { s -> choosing = false; withPermission { conn.connectTo(s.ssid!!, s.bssid) } },
            onRename = { renaming = it },
            onForget = conn::forget,
        )
    }
    renaming?.let { device ->
        RenameDialog(device, onDone = { name -> conn.rename(device, name); renaming = null }, onCancel = { renaming = null })
    }
}

/**
 * Known devices (select one to make it current), each with Rename / Forget in an overflow
 * menu, and "Pick a different device". Scopes in range come from the phone's last scan; when
 * the app can't see scans (no location access), nothing about range is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    book: DeviceBook,
    inRange: List<ScopeWifi.Identity>?,
    onDismiss: () -> Unit,
    onSelect: (KnownDevice) -> Unit,
    onPickDifferent: () -> Unit,
    onConnectNew: (ScopeWifi.Identity) -> Unit,
    onRename: (KnownDevice) -> Unit,
    onForget: (KnownDevice) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.known_devices),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (book.devices.isEmpty()) {
                Text(
                    stringResource(R.string.no_known_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Column(Modifier.selectableGroup()) {
                for (d in book.sorted) {
                    val near = inRange?.any { d.matches(it.ssid, it.bssid) }
                    val selected = d.key == book.lastKey
                    ListItem(
                        // The whole row is the radio button, for touch and for TalkBack.
                        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(d) }),
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        headlineContent = { Text(d.label) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    d.ssid.takeIf { d.nickname != null },
                                    DateUtils.getRelativeTimeSpanString(d.lastSeen).toString(),
                                    stringResource(R.string.in_range).takeIf { near == true },
                                ).joinToString(" · ")
                            )
                        },
                        trailingContent = { DeviceMenu(onRename = { onRename(d) }, onForget = { onForget(d) }) },
                    )
                }
            }
            // Scopes in the last scan that aren't known yet (only when scans are visible at all).
            inRange.orEmpty().filter { s -> s.ssid != null && book.devices.none { it.matches(s.ssid, s.bssid) } }.forEach { s ->
                ListItem(
                    modifier = Modifier.clickable { onConnectNew(s) },
                    headlineContent = { Text(s.ssid!!) },
                    supportingContent = { Text(stringResource(R.string.in_range_new)) },
                )
            }
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(onClick = onPickDifferent),
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.pick_different)) },
                supportingContent = { Text(stringResource(R.string.pick_different_hint)) },
            )
        }
    }
}

@Composable
private fun DeviceMenu(onRename: () -> Unit, onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.device_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.forget)) }, onClick = { open = false; onForget() })
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
        is ScopeWifi.State.Unavailable -> "no scope network (cancelled or not found)"
        is ScopeWifi.State.Failed -> "could not request the network: ${wifi.reason}"
    }
    val battery = s.battery?.let { "${it.percent}% (${it.stateName})" } ?: "–"
    return "$net · ${s.status}\n${s.fps} fps · battery $battery · " +
        "frames ${s.frames}, dropped ${s.dropped}, bad ${s.undecodable} · roll ${s.angle}°"
}
