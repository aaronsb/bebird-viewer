// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.capture.Capture
import com.bockelie.bebird.capture.CaptureFolder
import com.bockelie.bebird.capture.CaptureNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Snapshot, the most-used button (#54): filled and primary, a camera over the word, as tall as
 * [modifier] makes it. A short, crisp click on the press, before the release takes the picture.
 * TalkBack reads "Snapshot".
 */
@Composable
fun SnapshotButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TallButton(onClick, enabled, HoldVibrator::click, ButtonDefaults.buttonColors(), modifier) {
        Icon(painterResource(R.drawable.ic_camera), contentDescription = null)
        Text(stringResource(R.string.snapshot), maxLines = 1)
    }
}

/**
 * Record, big like Snapshot (#54): a dot over "Record"; while recording, in the error colours, a
 * square over "Stop" and the elapsed time (fixed-width digits). As wide as the widest of those
 * at the current font size, so starting, stopping or passing 10:00 never reflows the row. A
 * double click on the press, so it feels unlike Snapshot's single one.
 */
@Composable
fun RecordButton(since: Long?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val elapsed by produceState(0L, since) {
        while (since != null) {
            value = (SystemClock.elapsedRealtime() - since) / 1000
            delay(250)
        }
    }
    val colors = if (since == null) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    }
    // The widest of its labels, measured as drawn: "Record", "Stop" and a two-digit-minute time.
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val words = listOf(stringResource(R.string.record), stringResource(R.string.stop))
    val widest = (words.map { measurer.measure(it, style).size.width } + measurer.measure(WIDEST_TIME, style.merge(tabular)).size.width).max()
    val minWidth = with(LocalDensity.current) { widest.toDp() } + CompactPadding.calculateLeftPadding(LayoutDirection.Ltr) * 2
    TallButton(onClick, enabled || since != null, HoldVibrator::doubleClick, colors, modifier.widthIn(min = minWidth)) {
        if (since == null) {
            Icon(painterResource(R.drawable.ic_record), contentDescription = null)
            Text(stringResource(R.string.record), maxLines = 1)
        } else {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
            Text(stringResource(R.string.stop), maxLines = 1)
            Text("%d:%02d".format(elapsed / 60, elapsed % 60), maxLines = 1, style = LocalTextStyle.current.merge(tabular))
        }
    }
}

/** A filled button with its content stacked, as tall as [modifier] makes it, and [haptic] on the press. */
@Composable
private fun TallButton(
    onClick: () -> Unit,
    enabled: Boolean,
    haptic: (HoldVibrator) -> Unit,
    colors: ButtonColors,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val vibrator = remember(context) { HoldVibrator(context) }
    val interaction = remember { MutableInteractionSource() }
    val press by rememberUpdatedState(haptic)
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Press) press(vibrator) }
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = colors,
        interactionSource = interaction,
        contentPadding = CompactPadding,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** The widest time Record shows in practice: fixed-width digits, so any two-digit minute. */
private const val WIDEST_TIME = "00:00"

/** Narrower than a button's own padding, so the capture rows fit a 360 dp screen. */
val CompactPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** A short M3 snackbar for each saved capture, with Open. */
@Composable
fun CaptureSnackbar(vm: ViewerViewModel, host: SnackbarHostState) {
    val context = LocalContext.current
    val saved = stringResource(R.string.capture_saved)
    val failed = stringResource(R.string.capture_failed)
    val open = stringResource(R.string.capture_open)
    val noViewer = stringResource(R.string.capture_no_viewer)
    LaunchedEffect(Unit) {
        vm.captureResults.collect { result ->
            when (result) {
                is Capture.Result.Saved -> {
                    val action = host.showSnackbar(saved.format(result.name), actionLabel = open, duration = SnackbarDuration.Short)
                    if (action == SnackbarResult.ActionPerformed) {
                        val type = if (result.video) "video/mp4" else "image/jpeg"
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, type)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (!vm.launchOver(context, view)) host.showSnackbar(noViewer)
                    }
                }
                is Capture.Result.Failed -> host.showSnackbar(failed.format(result.what, result.reason))
                is Capture.Result.Problem -> host.showSnackbar(result.text)
            }
        }
    }
}

/** The document picker ([ActivityResultContracts.OpenDocument]) opening at [initialUri]. */
private class OpenCapture : ActivityResultContracts.OpenDocument() {
    var initialUri: Uri? = null

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply { initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
}

/**
 * Files: the system document picker, opening in today's capture folder (else Pictures/Bebird)
 * and listing images and videos; the picked file then opens in its default app. Both open over
 * the app, in its task, so Back returns here (see GraceKeeper.launchingOver). With no captures yet, or no
 * app to show a file, a snackbar says so.
 */
@Composable
fun FilesButton(vm: ViewerViewModel, host: SnackbarHostState, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val empty = stringResource(R.string.files_empty, CaptureNames.ROOT)
    val where = stringResource(R.string.files_where, CaptureNames.ROOT)
    val noViewer = stringResource(R.string.capture_no_viewer)
    val contract = remember { OpenCapture() }
    val picker = rememberLauncherForActivityResult(contract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!vm.launchOver(context, view)) scope.launch { host.showSnackbar(noViewer) }
    }
    FilledTonalButton(onClick = {
        scope.launch {
            when (val plan = vm.capturesPlan()) {
                CaptureFolder.Plan.Empty -> host.showSnackbar(empty)
                is CaptureFolder.Plan.Pick -> {
                    contract.initialUri = Uri.parse(plan.initialUri)
                    if (!vm.launchOver { picker.launch(CaptureFolder.MIME_TYPES) }) host.showSnackbar(where)
                }
            }
        }
    }, enabled = enabled, contentPadding = CompactPadding) {
        Text(stringResource(R.string.files))
    }
}
