// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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

/**
 * Files: opens the captures folder (Pictures/Bebird) in the phone's file browser, which then
 * opens each file with its default app. If nothing can show the folder, or there is none yet,
 * a snackbar says where captures go.
 */
@Composable
fun FilesButton(vm: ViewerViewModel, host: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val empty = stringResource(R.string.files_empty, CaptureNames.FOLDER)
    val where = stringResource(R.string.files_where, CaptureNames.FOLDER)
    FilledTonalButton(onClick = {
        scope.launch {
            val outcome = CaptureFolder.open(vm.capturesExist()) { attempt ->
                val intent = Intent(attempt.action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val uri = attempt.uri?.let(Uri::parse)
                when {
                    uri != null && attempt.type != null -> intent.setDataAndType(uri, attempt.type)
                    uri != null -> intent.setData(uri)
                    attempt.type != null -> intent.setType(attempt.type)
                }
                vm.launchOver(context, intent)  // over the app, in its task: Back returns here
            }
            when (outcome) {
                CaptureFolder.Outcome.Empty -> host.showSnackbar(empty)
                CaptureFolder.Outcome.Explain -> host.showSnackbar(where)
                is CaptureFolder.Outcome.Opened -> Unit
            }
        }
    }) {
        Text(stringResource(R.string.files))
    }
}
