// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.annotate.Mark
import com.bockelie.bebird.annotate.Palette
import com.bockelie.bebird.annotate.Sketch
import com.bockelie.bebird.annotate.Tool

/**
 * Four arrows out from the centre (Material's "open_with", Apache-2.0), for Move: the core
 * icon set has no such icon.
 */
private val MoveIcon: ImageVector = ImageVector.Builder("Move", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes("M10 9h4V6h3l-5-5-5 5h3v3zm-1 1H6V7l-5 5 5 5v-3h3v-4zm14 2l-5-5v3h-3v4h3v3l5-5zm-9 3h-4v3H7l5 5 5-5h-3v-3z"),
    fill = SolidColor(Color.Black),
).build()

/** Longest text label, in characters. */
private const val TEXT_MAX = 40

/**
 * The controls while annotating, in place of the live ones: the drawing tool, the colour,
 * Undo, Clear, Move (a toggle: drag a mark to move it, hold to delete), Resume (back to the
 * live view, dropping unsaved marks after a confirmation) and Save (both files, then back to
 * the live view; on a failure the marks stay for another try).
 * Back does what Resume does. Nothing changes while a save runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotateControls(
    tools: AnnotateTools, sketch: Sketch, saving: Boolean, savedOriginal: String?,
    onEdit: ((Sketch) -> Sketch) -> Unit, onResume: () -> Unit, onSave: () -> Unit,
) {
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    val leave = { if (sketch.marks.isEmpty()) onResume() else confirmingDiscard = true }
    BackHandler(enabled = !saving, onBack = leave)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hint = when (tools.tool) {
            Tool.TEXT -> R.string.annotate_hint_text
            Tool.MOVE -> R.string.annotate_hint_move
            else -> R.string.annotate_hint_draw
        }
        Text(stringResource(if (saving) R.string.annotate_saving else hint), style = MaterialTheme.typography.bodySmall)
        ToolRow(tools.tool) { tools.draw(it) }
        ColorRow(tools.color) { tools.color = it }
        // Resume and Save wrap onto a line of their own on a narrow screen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val centred = Modifier.align(Alignment.CenterVertically)
            TextButton(onClick = { onEdit(Sketch::undo) }, enabled = !saving && sketch.canUndo, modifier = centred) {
                Text(stringResource(R.string.annotate_undo))
            }
            TextButton(onClick = { onEdit(Sketch::clear) }, enabled = !saving && sketch.marks.isNotEmpty(), modifier = centred) {
                Text(stringResource(R.string.annotate_clear))
            }
            val moveName = stringResource(R.string.annotate_move_description)
            FilledTonalIconToggleButton(
                checked = tools.tool == Tool.MOVE,
                onCheckedChange = { tools.toggleMove() },
                enabled = !saving,
                modifier = centred.semantics { contentDescription = moveName },
            ) { Icon(MoveIcon, contentDescription = null) }
            Spacer(Modifier.weight(1f))
            Row(centred, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = leave, enabled = !saving) { Text(stringResource(R.string.annotate_resume)) }
                Button(onClick = onSave, enabled = !saving && sketch.marks.isNotEmpty()) { Text(stringResource(R.string.save)) }
            }
        }
    }
    tools.textAt?.let { at ->
        TextLabelDialog(
            onDone = { text ->
                if (text.isNotBlank()) onEdit { it.add(Mark.Text(at, text.trim(), tools.color)) }
                tools.textAt = null
            },
            onCancel = { tools.textAt = null },
        )
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.annotate_discard_title)) },
            // after a failed copy the picture itself is already saved; only the marks are lost
            text = {
                Text(
                    if (savedOriginal == null) stringResource(R.string.annotate_discard_message)
                    else stringResource(R.string.annotate_discard_message_saved, savedOriginal),
                )
            },
            confirmButton = { TextButton(onClick = { confirmingDiscard = false; onResume() }) { Text(stringResource(R.string.annotate_discard)) } },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.annotate_keep)) } },
        )
    }
}

/**
 * The drawing tools as one segmented control; short labels so five fit a phone's width, full
 * names for TalkBack. None is selected while Move is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolRow(tool: Tool, onTool: (Tool) -> Unit) {
    val options = listOf(
        Triple(Tool.ELLIPSE, R.string.annotate_ellipse_short, R.string.annotate_ellipse),
        Triple(Tool.BOX, R.string.annotate_box, R.string.annotate_box),
        Triple(Tool.ARROW, R.string.annotate_arrow, R.string.annotate_arrow),
        Triple(Tool.PEN, R.string.annotate_pen, R.string.annotate_pen),
        Triple(Tool.TEXT, R.string.annotate_text, R.string.annotate_text),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (t, label, name) ->
            val description = stringResource(name)
            SegmentedButton(
                selected = tool == t,
                onClick = { onTool(t) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                icon = {},  // no check mark: the labels keep their width
                modifier = Modifier.semantics { contentDescription = description },
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

/** The palette as a row of swatches, one selected (a ring around it). */
@Composable
private fun ColorRow(color: Int, onColor: (Int) -> Unit) {
    val names = mapOf(
        Palette.RED to R.string.color_red, Palette.YELLOW to R.string.color_yellow, Palette.GREEN to R.string.color_green,
        Palette.CYAN to R.string.color_cyan, Palette.WHITE to R.string.color_white,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.annotate_color), Modifier.padding(end = 8.dp))
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in Palette.ALL) {
                val name = stringResource(names.getValue(c))
                val selected = c == color
                Box(
                    Modifier.size(48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onColor(c) })
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    val ring = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
                    Box(Modifier.size(36.dp).then(ring).padding(if (selected) 5.dp else 2.dp).background(Color(c), CircleShape)
                        .border(1.dp, Color.Black, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun TextLabelDialog(onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.annotate_text_title)) },
        text = {
            OutlinedTextField(text, { text = it.take(TEXT_MAX) }, singleLine = true, label = { Text(stringResource(R.string.annotate_text_field)) })
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.annotate_text_add)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
