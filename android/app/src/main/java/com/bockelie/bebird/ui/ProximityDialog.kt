// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.focus.ProximityOptions

/**
 * Proximity settings: the master switch and the CLOSE indicator (greyed out, keeping its value,
 * while estimation is off). The scale style is chosen on the main screen, above Light.
 */
@Composable
fun ProximityDialog(
    state: ProximityOptions.State,
    onEnabled: (Boolean) -> Unit,
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
