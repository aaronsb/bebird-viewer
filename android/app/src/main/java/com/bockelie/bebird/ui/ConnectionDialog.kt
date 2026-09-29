// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.settings.Settings

/** The connection settings, as [ConnectionDialog] shows and changes them. */
data class ConnectionSettings(val autoConnect: Boolean, val graceSeconds: Int, val powerOffOnRelease: Boolean)

/**
 * The connection settings: connect to the last device at launch, how long the connection is kept
 * after leaving the app (#18, one of [Settings.GRACE_CHOICES]), and whether the scope is powered
 * off when the app lets go of it (#19). Changes apply at once; Done only closes.
 */
@Composable
fun ConnectionDialog(settings: ConnectionSettings, onChange: (ConnectionSettings) -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.connection_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow(stringResource(R.string.auto_connect), stringResource(R.string.auto_connect_hint), settings.autoConnect) {
                    onChange(settings.copy(autoConnect = it))
                }
                HorizontalDivider()
                Text(stringResource(R.string.leave_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.leave_message), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    for (s in Settings.GRACE_CHOICES) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = s == settings.graceSeconds, role = Role.RadioButton) {
                                    onChange(settings.copy(graceSeconds = s))
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s == settings.graceSeconds, onClick = null)
                            Text(graceLabel(s), Modifier.padding(start = 12.dp))
                        }
                    }
                }
                HorizontalDivider()
                SwitchRow(stringResource(R.string.grace_power_off), stringResource(R.string.grace_power_off_hint), settings.powerOffOnRelease) {
                    onChange(settings.copy(powerOffOnRelease = it))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.done)) } },
    )
}

/** A setting with a switch; the whole row toggles it, announced as a switch. */
@Composable
private fun SwitchRow(text: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun graceLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.grace_now)
    seconds < 60 -> stringResource(R.string.grace_seconds, seconds)
    seconds == 60 -> stringResource(R.string.grace_minute)
    else -> stringResource(R.string.grace_minutes, seconds / 60)
}
