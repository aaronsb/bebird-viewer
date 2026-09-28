// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
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
 * The gear menu: status band and circle outline, the name/label, and the theme. It takes
 * values and callbacks only, so it can be previewed and extended (e.g. power-off, #19).
 */
@Composable
fun SettingsMenu(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    band: Boolean,
    onBand: (Boolean) -> Unit,
    circle: Boolean,
    onCircle: (Boolean) -> Unit,
    label: String,
    onEditLabel: () -> Unit,
    onClearLabel: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CheckItem(stringResource(R.string.status_band), band) { onBand(!band) }
            CheckItem(stringResource(R.string.circle_outline), circle) { onCircle(!circle) }
            DropdownMenuItem(
                text = { Text(if (label.isEmpty()) stringResource(R.string.label_set) else stringResource(R.string.label_edit, label)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { open = false; onEditLabel() },
            )
            if (label.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.label_clear)) },
                    leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                    onClick = onClearLabel,
                )
            }
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

/** The free-text name/label: typed once, kept until changed or cleared. */
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
                // It may be a person's name: say plainly where it goes.
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
