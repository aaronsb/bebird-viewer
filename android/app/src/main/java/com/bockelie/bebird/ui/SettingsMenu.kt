// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.settings.Labels
import com.bockelie.bebird.settings.ThemeMode

/**
 * The gear menu: the overlay (status band and circle), the theme, and last Power off scope,
 * which works only once video has started ([onPowerOff] should ask for confirmation first).
 * It takes values and callbacks only, so it can be previewed and extended.
 */
@Composable
fun SettingsMenu(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    overlay: Boolean,
    onOverlay: (Boolean) -> Unit,
    canPowerOff: Boolean,
    onPowerOff: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CheckItem(stringResource(R.string.overlay), overlay) { onOverlay(!overlay) }
            HorizontalDivider()
            Text(
                stringResource(R.string.theme), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for ((m, name) in listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            )) {
                DropdownMenuItem(
                    text = { Text(stringResource(name)) },
                    leadingIcon = { RadioButton(selected = m == theme, onClick = null) },
                    onClick = { open = false; onTheme(m) },
                    // announced as a radio button with its state
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        selected = m == theme
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.power_off_title)) },
                enabled = canPowerOff,
                onClick = { open = false; onPowerOff() },
            )
        }
    }
}

/** A menu item with a checkbox, announced as a checkbox with its state. */
@Composable
private fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
    )
}

/** The free-text label: typed once, kept until changed or cleared. */
@Composable
fun LabelDialog(current: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.label_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = Labels.limit(it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                label = { Text(stringResource(R.string.label_field)) },
                // It may identify someone: say plainly where it goes.
                supportingText = { Text(stringResource(R.string.label_privacy)) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.label_clear))
                        }
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Confirm Power off scope: the scope stays off until its power button is pressed. */
@Composable
fun PowerOffDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.power_off_title)) },
        text = { Text(stringResource(R.string.power_off_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.power_off)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
