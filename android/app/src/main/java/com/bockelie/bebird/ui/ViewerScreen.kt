// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.bockelie.bebird.control.LightControl
import com.bockelie.bebird.control.RollFilter
import com.bockelie.bebird.settings.ThemeMode
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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

    val light by conn.lightState.collectAsStateWithLifecycle()
    val shownRoll by conn.shownRoll.collectAsStateWithLifecycle()
    val autoRotate by vm.autoRotate.collectAsStateWithLifecycle()
    val trim by vm.trim.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val online = wifi is ScopeWifi.State.Available

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The device selector: what Connect goes to, and where devices are managed.
                OutlinedButton(onClick = { choosing = true }, modifier = Modifier.weight(1f)) {
                    Text(book.last?.label ?: stringResource(R.string.no_device), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.choose_device))
                }
                Button(onClick = { if (idle) withPermission(conn::connect) else conn.disconnect() }) {
                    Text(stringResource(if (idle) R.string.connect else R.string.disconnect))
                }
                ThemeMenu(theme, vm::setTheme)
            }
            Text(
                statusLine(wifi, stats), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            ZoomableCircle(
                frame = stats.frame,
                rotation = RollFilter.rotation(shownRoll, autoRotate, trim),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Readouts(stats, shownRoll)
            LightRow(light, onToggle = conn::toggleLight, onLevel = conn::setLight)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoRotate, onCheckedChange = vm::setAutoRotate)
                Text(stringResource(R.string.auto_rotate), Modifier.padding(start = 8.dp).weight(1f))
                TextButton(onClick = { vm.stepTrim(-1) }) { Text(stringResource(R.string.trim_minus)) }
                Text(
                    stringResource(R.string.trim_value, signed(trim, 4)),
                    style = MaterialTheme.typography.bodyMedium.merge(tabular),
                )
                TextButton(onClick = { vm.stepTrim(1) }) { Text(stringResource(R.string.trim_plus)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Capture comes with #15.
                FilledTonalButton(onClick = {}, enabled = false) { Text(stringResource(R.string.snapshot)) }
                FilledTonalButton(onClick = {}, enabled = false) { Text(stringResource(R.string.record)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = conn::reconnect, enabled = online) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.reconnect), Modifier.padding(start = 4.dp))
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
    return "$net · ${s.status}"
}

/** Digits all the same width, so changing numbers don't shift the layout. */
private val tabular = TextStyle(fontFeatureSettings = "tnum")

/** [n] right-aligned in [width] characters, padded with figure spaces (as wide as a digit). */
private fun fixed(n: Int, width: Int) = n.toString().padStart(width, '\u2007')

private fun signed(n: Int, width: Int) = (if (n > 0) "+$n" else "$n").padStart(width, '\u2007')

@Composable
private fun Readouts(s: ScopeSession.Stats, roll: Int) {
    val battery = s.battery
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.labelLarge.merge(tabular)
        Text(
            if (battery == null) stringResource(R.string.battery_unknown)
            else stringResource(R.string.battery_value, fixed(battery.percent, 3), if (battery.state == 2) "+" else "\u2007"),
            style = style,
        )
        Text(stringResource(R.string.fps_value, fixed(s.fps, 2)), style = style)
        Text(stringResource(R.string.roll_value, fixed(roll, 3)), style = style)
        Text(stringResource(R.string.dropped_value, fixed(s.dropped, 5)), style = style)
    }
}

@Composable
private fun LightRow(light: ScopeConnection.Light, onToggle: () -> Unit, onLevel: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = light.level > 0, onCheckedChange = { onToggle() })
        Text(stringResource(R.string.light), Modifier.padding(horizontal = 8.dp))
        Slider(
            value = light.level.toFloat(),
            onValueChange = { onLevel(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
        Text(
            fixed(light.level, 3) + "%",
            style = MaterialTheme.typography.bodyMedium.merge(tabular),
            modifier = Modifier.padding(start = 8.dp),
        )
        // Whether the scope confirmed the level (read back after it was sent).
        val (mark, what) = when (val st = light.status) {
            LightControl.Status.Idle -> "\u2007" to R.string.light_idle
            LightControl.Status.Pending, is LightControl.Status.Verifying -> "…" to R.string.light_verifying
            is LightControl.Status.Confirmed -> "✓" to R.string.light_confirmed
            is LightControl.Status.Mismatch -> "!" to R.string.light_mismatch
        }
        val desc = stringResource(what)
        Text(
            mark,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(20.dp).padding(start = 4.dp).semantics { contentDescription = desc },
        )
    }
}

/**
 * The image circle inside a rectangular viewport. Pinch zooms (1-6x) and drag pans, clipped
 * to the viewport; double-tap resets. Display only: nothing here changes what is received.
 */
@Composable
private fun ZoomableCircle(frame: Bitmap?, rotation: Int, modifier: Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val side = minOf(w, h)
        // How far the circle may move: only as far as it overhangs the viewport.
        fun clamp(o: Offset, z: Float) = Offset(
            o.x.coerceIn(-maxOf(0f, (side * z - w) / 2), maxOf(0f, (side * z - w) / 2)),
            o.y.coerceIn(-maxOf(0f, (side * z - h) / 2), maxOf(0f, (side * z - h) / 2)),
        )
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = 1f; offset = Offset.Zero }) }
                .pointerInput(side) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                        offset = clamp(offset + pan, zoom)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(with(LocalDensity.current) { side.toDp() })
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .clip(CircleShape)
                    .background(Color.Black),
            ) {
                frame?.let {
                    // Rotated clockwise by the roll angle (and trim) keeps the picture upright.
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().rotate(rotation.toFloat()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeMenu(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.theme))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.theme), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for ((m, label) in listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            )) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = m == mode, onClick = null) },
                    onClick = { open = false; onMode(m) },
                )
            }
        }
    }
}
