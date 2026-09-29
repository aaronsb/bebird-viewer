// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.focus.ProximityOptions
import com.bockelie.bebird.focus.ScaleStyle

/**
 * Proximity settings: the master switch, the scale style and the CLOSE indicator. The two
 * sub-settings are greyed out (but keep their values) while estimation is off.
 */
@Composable
fun ProximityDialog(
    state: ProximityOptions.State,
    onEnabled: (Boolean) -> Unit,
    onStyle: (ScaleStyle) -> Unit,
    onClose: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proximity_title)) },
        text = {
            Column {
                SwitchRow(stringResource(R.string.proximity_enabled), state.enabled, enabled = true, onEnabled)
                Text(
                    stringResource(R.string.proximity_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                val sub = state.subSettingsEnabled
                Text(
                    stringResource(R.string.proximity_scale),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.alpha(if (sub) 1f else DISABLED_ALPHA).semantics { heading() },
                )
                Column(Modifier.selectableGroup()) {
                    for ((style, name) in listOf(
                        ScaleStyle.RING to R.string.proximity_scale_ring,
                        ScaleStyle.BOWTIE to R.string.proximity_scale_bowtie,
                        ScaleStyle.BAR to R.string.proximity_scale_bar,
                        ScaleStyle.NONE to R.string.proximity_scale_off,
                    )) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = state.style == style, enabled = sub, role = Role.RadioButton) { onStyle(style) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.style == style, onClick = null, enabled = sub)
                            Text(stringResource(name), Modifier.padding(start = 12.dp).alpha(if (sub) 1f else DISABLED_ALPHA))
                        }
                    }
                }
                SwitchRow(stringResource(R.string.proximity_close), state.close, enabled = sub, onClose)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private const val DISABLED_ALPHA = 0.38f
