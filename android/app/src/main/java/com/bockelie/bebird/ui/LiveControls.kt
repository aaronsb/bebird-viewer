// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bockelie.bebird.R
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.control.LightControl
import com.bockelie.bebird.focus.ScaleStyle
import com.bockelie.bebird.wifi.ScopeWifi
import kotlin.math.roundToInt

/**
 * The controls under the live view: the scale style, Light, auto-rotate and trim, the label,
 * and the capture buttons. [streaming] while frames are arriving (captures only then; they
 * never talk to the scope). Annotate swaps these for [AnnotateControls].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveControls(
    vm: ViewerViewModel, zoomView: ZoomView, snackbar: SnackbarHostState, frame: Bitmap?, streaming: Boolean,
    onEditLabel: () -> Unit, onAnnotate: () -> Unit,
) {
    val conn = vm.connection
    val proximityState by vm.proximity.options.state.collectAsStateWithLifecycle()
    val light by conn.lightState.collectAsStateWithLifecycle()
    val autoRotate by vm.autoRotate.collectAsStateWithLifecycle()
    val trim by vm.trim.collectAsStateWithLifecycle()
    val label by vm.label.collectAsStateWithLifecycle()
    val recordingSince by vm.recordingSince.collectAsStateWithLifecycle()
    val annotationRenderer by vm.annotationRenderer.collectAsStateWithLifecycle()
    val online = conn.wifiState.collectAsStateWithLifecycle().value is ScopeWifi.State.Available
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The scale style lives here, above Light; greyed out (keeping its value, and the
        // row's height) while proximity estimation is off.
        ScaleRow(proximityState.style, enabled = proximityState.enabled, onStyle = vm.proximity.options::setStyle)
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
        LabelRow(label, onEdit = onEditLabel, onClear = { vm.setLabel("") })
        // Wraps onto a second line on a narrow screen rather than squeezing the buttons.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { vm.snapshot(zoomView.zoom, frame?.let { zoomView.crop(it.width) }) },
                enabled = streaming,
            ) { Text(stringResource(R.string.snapshot)) }
            RecordButton(recordingSince, enabled = streaming, onClick = vm::toggleRecording)
            // Not while recording: the file would carry on behind the paused view.
            FilledTonalButton(
                onClick = onAnnotate,
                enabled = streaming && recordingSince == null && annotationRenderer != null,
            ) { Text(stringResource(R.string.annotate)) }
            FilesButton(vm, snackbar)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = conn::reconnect, enabled = online) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(stringResource(R.string.reconnect), Modifier.padding(start = 4.dp))
            }
        }
    }
}

private fun signed(n: Int, width: Int) = (if (n > 0) "+$n" else "$n").padStart(width, '\u2007')

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

/** The proximity scale's style: Ring / Bowtie / Bar / Off, as one segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaleRow(style: ScaleStyle, enabled: Boolean, onStyle: (ScaleStyle) -> Unit) {
    val options = listOf(
        ScaleStyle.RING to R.string.proximity_scale_ring,
        ScaleStyle.BOWTIE to R.string.proximity_scale_bowtie,
        ScaleStyle.BAR to R.string.proximity_scale_bar,
        ScaleStyle.NONE to R.string.proximity_scale_off,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.proximity_scale), Modifier.padding(end = 8.dp).alpha(if (enabled) 1f else 0.38f))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            options.forEachIndexed { i, (s, name) ->
                SegmentedButton(
                    selected = style == s,
                    onClick = { onStyle(s) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    icon = {},  // no check mark: the labels keep their width
                ) { Text(stringResource(name), maxLines = 1) }
            }
        }
    }
}
