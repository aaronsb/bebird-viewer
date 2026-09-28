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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.bockelie.bebird.control.LightControl
import com.bockelie.bebird.control.RollFilter
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Dp
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import android.content.Intent
import android.os.SystemClock
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import com.bockelie.bebird.capture.Capture
import com.bockelie.bebird.capture.ZoomCrop
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
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
    val overlayOn by vm.overlay.collectAsStateWithLifecycle()
    val label by vm.label.collectAsStateWithLifecycle()
    val renderer by vm.bandRenderer.collectAsStateWithLifecycle()
    val online = wifi is ScopeWifi.State.Available
    val canPowerOff by conn.isStreaming.collectAsStateWithLifecycle()
    var confirmingPowerOff by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf(false) }
    // The band's clock, on the second.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            value = LocalDateTime.now()
        }
    }
    // Video gone while the confirmation is open: Power off would do nothing, so close it.
    LaunchedEffect(canPowerOff) { if (!canPowerOff) confirmingPowerOff = false }

    val zoomView = remember { ZoomView() }
    val recordingSince by vm.recordingSince.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CaptureSnackbar(vm, snackbar)

    Scaffold(modifier = Modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
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
                val theme by vm.theme.collectAsStateWithLifecycle()
                SettingsMenu(
                    theme = theme, onTheme = vm::setTheme, overlay = overlayOn, onOverlay = vm::setOverlay,
                    canPowerOff = canPowerOff, onPowerOff = { confirmingPowerOff = true },
                )
            }
            Text(
                statusLine(wifi, stats), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Viewport and band as one panel: no gap between them.
            Column(Modifier.fillMaxWidth().weight(1f)) {
                ZoomableCircle(
                    frame = stats.frame,
                    rotation = RollFilter.rotation(shownRoll, autoRotate, trim),
                    outline = overlayOn,
                    view = zoomView,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                StatusBandSlot(
                    renderer = renderer,
                    showBand = overlayOn,
                    data = bandDataOf(stats, light, shownRoll, trim, book.last, online, label, now),
                ) { Readouts(stats, shownRoll) }
            }
            LightRow(light, onToggle = conn::toggleLight, onLevel = conn::setLight)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The switch and its label are one control, so TalkBack names it.
                Row(
                    Modifier.weight(1f).toggleable(value = autoRotate, role = Role.Switch, onValueChange = vm::setAutoRotate),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = autoRotate, onCheckedChange = null)
                    Text(stringResource(R.string.auto_rotate), Modifier.padding(start = 8.dp))
                }
                val minus = stringResource(R.string.trim_minus_description)
                val plus = stringResource(R.string.trim_plus_description)
                TextButton(onClick = { vm.stepTrim(-1) }, modifier = Modifier.semantics { contentDescription = minus }) {
                    Text(stringResource(R.string.trim_minus))
                }
                Text(
                    stringResource(R.string.trim_value, signed(trim, 4)),
                    style = MaterialTheme.typography.bodyMedium.merge(tabular),
                )
                TextButton(onClick = { vm.stepTrim(1) }, modifier = Modifier.semantics { contentDescription = plus }) {
                    Text(stringResource(R.string.trim_plus))
                }
            }
            LabelRow(label, onEdit = { editingLabel = true }, onClear = { vm.setLabel("") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only while frames are arriving; captures never talk to the scope.
                // canPowerOff is ScopeConnection.isStreaming: this session has shown a frame
                val streaming = canPowerOff && stats.frame != null
                FilledTonalButton(
                    onClick = { vm.snapshot(zoomView.zoom, stats.frame?.let { zoomView.crop(it.width) }) },
                    enabled = streaming,
                ) { Text(stringResource(R.string.snapshot)) }
                RecordButton(recordingSince, enabled = streaming, onClick = vm::toggleRecording)
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
    if (editingLabel) {
        LabelDialog(label, onDone = { vm.setLabel(it); editingLabel = false }, onCancel = { editingLabel = false })
    }
    renaming?.let { device ->
        RenameDialog(device, onDone = { name -> conn.rename(device, name); renaming = null }, onCancel = { renaming = null })
    }
    if (confirmingPowerOff) {
        PowerOffDialog(onConfirm = { confirmingPowerOff = false; conn.powerOff() }, onCancel = { confirmingPowerOff = false })
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
        Row(
            Modifier.toggleable(value = light.level > 0, role = Role.Switch, onValueChange = { onToggle() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = light.level > 0, onCheckedChange = null)
            Text(stringResource(R.string.light), Modifier.padding(horizontal = 8.dp))
        }
        val levelName = stringResource(R.string.light_level)
        val levelState = stringResource(R.string.light_level_state, light.level)
        Slider(
            value = light.level.toFloat(),
            onValueChange = { onLevel(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = levelName
                stateDescription = levelState
            },
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
private fun ZoomableCircle(frame: Bitmap?, rotation: Int, outline: Boolean, view: ZoomView, modifier: Modifier) {
    // Outside the image circle the viewport is the band's black, not the theme's surface, so
    // image and band read as one panel (as in saved stills with the overlay).
    BoxWithConstraints(modifier.clipToBounds().background(Color(BandRenderer.BACKGROUND)), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val side = minOf(w, h)
        SideEffect { view.viewportW = w; view.viewportH = h }  // for the zoomed snapshot's crop
        // How far the circle may move: only as far as it overhangs the viewport.
        fun clamp(o: Offset, z: Float) = Offset(
            o.x.coerceIn(-maxOf(0f, (side * z - w) / 2), maxOf(0f, (side * z - w) / 2)),
            o.y.coerceIn(-maxOf(0f, (side * z - h) / 2), maxOf(0f, (side * z - h) / 2)),
        )
        // A resize (rotation, multi-window) keeps the view inside the new bounds.
        LaunchedEffect(w, h) { view.offset = clamp(view.offset, view.zoom) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { view.zoom = 1f; view.offset = Offset.Zero }) }
                .pointerInput(w, h) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val newZoom = (view.zoom * gestureZoom).coerceIn(1f, 6f)
                        // Keep the point under the fingers where it is: relative to the viewport
                        // centre, o' = (o - p) * z'/z + p, then add the pan.
                        val p = centroid - Offset(w / 2, h / 2)
                        view.offset = clamp((view.offset - p) * (newZoom / view.zoom) + p + pan, newZoom)
                        view.zoom = newZoom
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(with(LocalDensity.current) { side.toDp() })
                    .graphicsLayer {
                        scaleX = view.zoom
                        scaleY = view.zoom
                        translationX = view.offset.x
                        translationY = view.offset.y
                    }
                    .clip(CircleShape)
                    .background(Color.Black)
                    // the same hair-thin ring saved images get (#15)
                    .then(if (outline) Modifier.border(Dp.Hairline, Color(BandRenderer.CIRCLE), CircleShape) else Modifier),
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

/**
 * The label, like Trim a control of its own: the current text (or a prompt) opens the editing
 * dialog, and a clear button next to it. Its height doesn't depend on the text.
 */
@Composable
private fun LabelRow(label: String, onEdit: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.label), Modifier.padding(end = 8.dp))
        val description = if (label.isEmpty()) stringResource(R.string.label_add) else stringResource(R.string.label_edit_description, label)
        OutlinedButton(
            onClick = onEdit,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        ) {
            Text(
                label.ifEmpty { stringResource(R.string.label_add) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Disabled rather than hidden when there's nothing to clear, so the row never changes shape.
        IconButton(onClick = onClear, enabled = label.isNotEmpty()) {
            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.label_clear))
        }
    }
}

/** The viewport's zoom and pan, kept outside it so a snapshot can crop what is visible. */
@Stable
class ZoomView {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewportW = 0f
    var viewportH = 0f

    /** The visible part of a [frameSize]-px frame, or null when not zoomed in. */
    fun crop(frameSize: Int): ZoomCrop.Rect? =
        ZoomCrop.visible(frameSize, viewportW, viewportH, minOf(viewportW, viewportH), zoom, offset.x, offset.y)
}

/** Record, or Stop with the elapsed time (fixed-width digits) while recording. */
@Composable
private fun RecordButton(since: Long?, enabled: Boolean, onClick: () -> Unit) {
    val elapsed by produceState(0L, since) {
        while (since != null) {
            value = (SystemClock.elapsedRealtime() - since) / 1000
            delay(250)
        }
    }
    FilledTonalButton(onClick = onClick, enabled = enabled || since != null) {
        if (since == null) {
            Text(stringResource(R.string.record))
        } else {
            Text(stringResource(R.string.record_stop, "%d:%02d".format(elapsed / 60, elapsed % 60)), style = LocalTextStyle.current.merge(tabular))
        }
    }
}

/** A short M3 snackbar for each saved capture, with Open. */
@Composable
private fun CaptureSnackbar(vm: ViewerViewModel, host: SnackbarHostState) {
    val context = LocalContext.current
    val saved = stringResource(R.string.capture_saved)
    val failed = stringResource(R.string.capture_failed)
    val open = stringResource(R.string.capture_open)
    LaunchedEffect(Unit) {
        vm.captureResults.collect { result ->
            when (result) {
                is Capture.Result.Saved -> {
                    val action = host.showSnackbar(saved.format(result.name), actionLabel = open, duration = SnackbarDuration.Short)
                    if (action == SnackbarResult.ActionPerformed) {
                        val type = if (result.video) "video/mp4" else "image/jpeg"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, type)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }
                }
                is Capture.Result.Failed -> host.showSnackbar(failed.format(result.what, result.reason))
            }
        }
    }
}
