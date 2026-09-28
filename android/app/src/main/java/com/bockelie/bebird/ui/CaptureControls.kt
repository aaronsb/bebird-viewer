// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bockelie.bebird.R
import com.bockelie.bebird.capture.Capture
import com.bockelie.bebird.capture.CaptureFolder
import com.bockelie.bebird.capture.CaptureNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Record, or Stop with the elapsed time (fixed-width digits) while recording. */
@Composable
fun RecordButton(since: Long?, enabled: Boolean, onClick: () -> Unit) {
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
 * the app, in its task, so Back returns here (see ExternalLaunch). With no captures yet, or no
 * app to show a file, a snackbar says so.
 */
@Composable
fun FilesButton(vm: ViewerViewModel, host: SnackbarHostState) {
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
    }) {
        Text(stringResource(R.string.files))
    }
}
