// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.capture.Capture
import com.bockelie.bebird.capture.CaptureFolder
import com.bockelie.bebird.capture.CaptureNames
import com.bockelie.bebird.capture.SnapshotMeta
import com.bockelie.bebird.capture.ZoomCrop
import com.bockelie.bebird.proto.Protocol
import com.bockelie.bebird.wifi.ScopeWifi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.BebirdApp
import com.bockelie.bebird.band.BandFonts
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.control.RollFilter
import com.bockelie.bebird.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The view settings for the screen, and the app's [ScopeConnection] (owned by [BebirdApp], so it
 * can outlive the screen for the background grace period; MainActivity decides when it ends).
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as BebirdApp).settings
    val connection: ScopeConnection = (app as BebirdApp).connection
    private val grace = (app as BebirdApp).grace

    private val _autoRotate = MutableStateFlow(settings.autoRotate)
    val autoRotate: StateFlow<Boolean> = _autoRotate.asStateFlow()
    private val _trim = MutableStateFlow(settings.trim)
    val trim: StateFlow<Int> = _trim.asStateFlow()
    private val _theme = MutableStateFlow(settings.theme)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _connectionSettings = MutableStateFlow(
        ConnectionSettings(settings.autoConnect, settings.graceSeconds, settings.powerOffAfterGrace),
    )
    val connectionSettings: StateFlow<ConnectionSettings> = _connectionSettings.asStateFlow()

    private val _overlay = MutableStateFlow(settings.overlay)
    val overlay: StateFlow<Boolean> = _overlay.asStateFlow()
    private val _showScopeId = MutableStateFlow(settings.showScopeId)
    /** Whether the band and saved files carry the scope's unique ID (off by default). */
    val showScopeId: StateFlow<Boolean> = _showScopeId.asStateFlow()
    private val _label = MutableStateFlow(settings.label)
    val label: StateFlow<String> = _label.asStateFlow()

    // The band's fonts take a moment to parse; the band appears once they have.
    private val _bandRenderer = MutableStateFlow<BandRenderer?>(null)
    val bandRenderer: StateFlow<BandRenderer?> = _bandRenderer.asStateFlow()

    init {
        Log.i("BebirdSpike", "ViewModel created (${Integer.toHexString(System.identityHashCode(this))})")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _bandRenderer.value = BandRenderer(BandFonts.shared(app.assets))
            } catch (e: Exception) {
                Log.e("BebirdSpike", "band fonts failed to load", e)
            }
        }
    }

    fun setShowScopeId(on: Boolean) {
        settings.showScopeId = on
        _showScopeId.value = on
    }

    fun setOverlay(on: Boolean) {
        settings.overlay = on
        _overlay.value = on
    }

    /** A blank label clears it. */
    fun setLabel(text: String) {
        settings.label = text
        _label.value = settings.label
    }

    fun setAutoRotate(on: Boolean) {
        settings.autoRotate = on
        _autoRotate.value = on
    }

    /** One trim step (±15°), as the desktop's [ and ] keys. */
    fun stepTrim(steps: Int) {
        val t = RollFilter.stepTrim(_trim.value, steps)
        settings.trim = t
        _trim.value = t
    }

    fun setTheme(mode: ThemeMode) {
        settings.theme = mode
        _theme.value = mode
    }

    fun setConnectionSettings(c: ConnectionSettings) {
        settings.autoConnect = c.autoConnect
        settings.graceSeconds = c.graceSeconds
        settings.powerOffAfterGrace = c.powerOffOnRelease
        _connectionSettings.value = c.copy(graceSeconds = settings.graceSeconds)
    }

    // --- capture (#15) ---

    private val capture = Capture(app.contentResolver)
    private val _recordingSince = MutableStateFlow<Long?>(null)  // elapsedRealtime at start
    /** When the current recording started (elapsedRealtime ms), or null when not recording. */
    val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()
    private var recordingOverlay = false  // fixed for the whole file
    private val _captureResults = MutableSharedFlow<Capture.Result>(extraBufferCapacity = 4)
    /** Each saved (or failed) snapshot or recording, for the snackbar. */
    val captureResults: SharedFlow<Capture.Result> = _captureResults.asSharedFlow()

    init {
        viewModelScope.launch {
            connection.stats.map { it.frame }.distinctUntilChanged().collect { frame ->
                if (_recordingSince.value == null) return@collect
                if (frame == null) stopRecording()  // the stream stopped
                else shot(overlay = recordingOverlay)?.let { capture.addVideoFrame(it, SystemClock.elapsedRealtimeNanos()) }
            }
        }
    }

    private val online get() = connection.wifiState.value is ScopeWifi.State.Available

    /** The band as it shows now; the screen uses the same function, so captures match it. */
    fun bandData(now: LocalDateTime): BandData = bandDataOf(
        connection.stats.value, connection.lightState.value, connection.shownRoll.value, _trim.value,
        connection.book.value.last, online, _label.value, now, _showScopeId.value,
    )

    /** The current frame as shown, with its metadata; null when there is no frame. */
    private fun shot(overlay: Boolean = _overlay.value, zoom: Float = 1f, zoomRect: ZoomCrop.Rect? = null): Capture.Shot? {
        val stats = connection.stats.value
        val frame = stats.frame ?: return null
        val taken = ZonedDateTime.now()
        val light = connection.lightState.value
        val rotation = RollFilter.rotation(connection.shownRoll.value, _autoRotate.value, _trim.value)
        val meta = SnapshotMeta(
            taken = taken,
            roll = stats.angle,
            rotationApplied = rotation,
            autoRotate = _autoRotate.value,
            trim = _trim.value,
            lightPercent = light.level,
            lightRaw = Protocol.lightPercentToRaw(light.level),
            batteryPercent = stats.battery?.percent,
            batteryState = stats.battery?.stateName,
            fps = stats.fps,
            zoom = zoom.toDouble(),
            zoomed = false,
            label = _label.value.ifEmpty { null },
            device = bandData(taken.toLocalDateTime()).device,  // exactly what the band shows
            model = stats.beacon?.model,
        )
        return Capture.Shot(frame, rotation, overlay, _bandRenderer.value, bandData(taken.toLocalDateTime()), meta, zoomRect)
    }

    /** Save the frame as shown; when zoomed in ([zoomRect] non-null), the visible crop too. */
    fun snapshot(zoom: Float, zoomRect: ZoomCrop.Rect?) {
        val shot = shot(zoom = zoom, zoomRect = zoomRect) ?: return
        capture.snapshot(shot) { _captureResults.tryEmit(it) }
    }

    // --- another app over ours (Files, Open) ---

    /**
     * Start [intent] over this app, in its task (Back returns to the viewer). Leaving for it
     * keeps the connection; see [GraceKeeper.launchingOver]. Returns false if nothing could
     * handle it.
     */
    fun launchOver(context: Context, intent: Intent): Boolean = launchOver { context.startActivity(intent) }

    /** Run [start] (which starts another activity over this app, e.g. a picker) under the same cover. */
    fun launchOver(start: () -> Unit): Boolean {
        grace.launchingOver()
        return try {
            start()
            true
        } catch (_: ActivityNotFoundException) {
            grace.launchFailed(); false
        } catch (_: SecurityException) {
            grace.launchFailed(); false
        }
    }

    /** Where Files should open the picker (or that there is nothing yet), from MediaStore. */
    suspend fun capturesPlan(): CaptureFolder.Plan = withContext(Dispatchers.IO) {
        CaptureFolder.plan(capture.hasCaptures(), LocalDate.now(), capture::hasCapturesIn)
    }

    fun toggleRecording() = if (_recordingSince.value == null) startRecording() else stopRecording()

    private fun startRecording() {
        recordingOverlay = _overlay.value
        val first = shot(overlay = recordingOverlay) ?: return
        val name = CaptureNames.video(first.meta.taken.toLocalDateTime())
        if (recordingOverlay && first.renderer == null) {
            // the band's size would change once the fonts arrive, mid-file
            _captureResults.tryEmit(Capture.Result.Failed(name, "the overlay is still loading; try again in a moment"))
            return
        }
        // Recording as far as the UI knows from now on; whatever ends it (stop, a failed start,
        // an encoder error) resets this and reports through the snackbar.
        _recordingSince.value = SystemClock.elapsedRealtime()
        capture.startRecording(name, first) { result ->
            _recordingSince.value = null
            _captureResults.tryEmit(result)
        }
    }

    /** Finish the recording, if any (also when leaving the app: recording is foreground-only for now). */
    fun stopRecording() {
        if (_recordingSince.value == null) return
        _recordingSince.value = null  // the UI stops at once; the file is finished on the worker
        capture.stopRecording { _captureResults.tryEmit(it) }
    }

    override fun onCleared() {
        Log.i("BebirdSpike", "ViewModel cleared")
        stopRecording()
        capture.close()
        // Not the connection: it belongs to the app and outlives the screen (see GraceKeeper).
    }
}
