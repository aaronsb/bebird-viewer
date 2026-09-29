// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.annotate.AnnotateRules
import com.bockelie.bebird.annotate.AnnotationRenderer
import com.bockelie.bebird.annotate.SaveProgress
import com.bockelie.bebird.annotate.Sketch
import com.bockelie.bebird.capture.Capture
import com.bockelie.bebird.capture.CaptureFolder
import com.bockelie.bebird.capture.CaptureNames
import com.bockelie.bebird.capture.Frames
import com.bockelie.bebird.capture.SnapshotMeta
import com.bockelie.bebird.capture.ZoomCrop
import com.bockelie.bebird.proto.Protocol
import com.bockelie.bebird.wifi.ScopeWifi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.concurrent.Future
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.BebirdApp
import com.bockelie.bebird.R
import com.bockelie.bebird.band.BandFonts
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.ProximityPipeline
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.band.toBitmap
import com.bockelie.bebird.capture.SavedScale
import com.bockelie.bebird.capture.ScaleStamp
import com.bockelie.bebird.focus.ScaleStyle
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.control.RollFilter
import com.bockelie.bebird.settings.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The view settings for the screen, and the app's [ScopeConnection] (owned by [BebirdApp], so it
 * can outlive the screen for the background grace period; MainActivity decides when it ends).
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as BebirdApp).settings
    val connection: ScopeConnection = (app as BebirdApp).connection

    /** BATTERY LOW (#48), with its hysteresis kept here so rotation and annotate don't reset it. */
    val batteryLow: StateFlow<Boolean> =
        BatteryLow.latch(connection.stats.map { it.battery }).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Proximity estimation (#27): its settings and the latest result, app-scoped like the connection. */
    val proximity: ProximityPipeline = (app as BebirdApp).proximity
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
    // The proximity overlay draws its labels with the band's font.
    private val _overlayRenderer = MutableStateFlow<OverlayRenderer?>(null)
    val overlayRenderer: StateFlow<OverlayRenderer?> = _overlayRenderer.asStateFlow()
    // So do annotations' text labels.
    private val _annotationRenderer = MutableStateFlow<AnnotationRenderer?>(null)
    val annotationRenderer: StateFlow<AnnotationRenderer?> = _annotationRenderer.asStateFlow()
    // And the viewport's own text: the empty circle's status and the scale's note.
    private val _pixelText = MutableStateFlow<PixelText?>(null)
    val pixelText: StateFlow<PixelText?> = _pixelText.asStateFlow()

    // Draws the proximity scale into saved stills (#43), once the fonts are in.
    @Volatile private var scaleStamp: ScaleStamp? = null

    /** The proximity scale on screen now, as a saved still carries it: none when estimation or the scale is off. */
    private fun scaleShown(): SavedScale? {
        val result = proximity.result.value ?: return null
        val style = proximity.options.state.value.style
        return if (style == ScaleStyle.NONE) null else SavedScale(style, result.locked)
    }

    init {
        Log.i("BebirdSpike", "ViewModel created (${Integer.toHexString(System.identityHashCode(this))})")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fonts = BandFonts.shared(app.assets)
                _bandRenderer.value = BandRenderer(fonts)
                _overlayRenderer.value = OverlayRenderer(fonts)
                _annotationRenderer.value = AnnotationRenderer(fonts)
                _pixelText.value = PixelText(fonts)
                scaleStamp = ScaleStamp(OverlayRenderer(fonts), PixelText(fonts))
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
        return Capture.Shot(
            frame, rotation, overlay, _bandRenderer.value, bandData(taken.toLocalDateTime()), meta, zoomRect,
            scale = scaleShown(), stamp = scaleStamp,
        )
    }

    /** Save the frame as shown; when zoomed in ([zoomRect] non-null), the visible crop too. */
    fun snapshot(zoom: Float, zoomRect: ZoomCrop.Rect?) {
        val shot = shot(zoom = zoom, zoomRect = zoomRect) ?: return
        capture.snapshot(shot) { _captureResults.tryEmit(it) }
    }

    // --- annotate (#16) ---

    /**
     * A paused frame being annotated: [shot] as it was when paused, its frame already turned
     * upright (rotation 0; the meta keeps the rotation applied), the marks so far, and how
     * saving them stands.
     */
    class Annotating(
        val shot: Capture.Shot, val sketch: Sketch, val progress: SaveProgress = SaveProgress(),
        /** The proximity scale shown when pausing (and saved on the annotated copy), as drawn over the frame; null if none. */
        val scaleLayer: Bitmap? = null,
    )

    private val _annotating = MutableStateFlow<Annotating?>(null)
    /** The paused frame and its marks while annotating, else null (the live view). */
    val annotating: StateFlow<Annotating?> = _annotating.asStateFlow()
    private var pausing: Job? = null  // turning the paused frame upright, off the main thread

    /**
     * Pause on the current frame to annotate it. Only the view pauses: the session keeps
     * receiving (and keeping the scope alive) as before. Not while recording. The frame is
     * held from now, so a disconnect while it is being turned upright changes nothing.
     */
    fun startAnnotating(zoom: Float) {
        if (!AnnotateRules.canAnnotate(_recordingSince.value != null, _annotating.value != null, pausing != null)) return
        val s = shot(zoom = zoom) ?: return
        pausing = viewModelScope.launch {
            try {
                val (upright, drawnScale) = withContext(Dispatchers.Default) {
                    val upright = Frames.rotated(s.frame, s.rotation)
                    // If the scale can't be drawn, pause without it (and save none) rather than not at all.
                    upright to ScaleStamp.drawnOrNone(s.scale, { Log.e("BebirdSpike", "scale not drawn on the paused frame", it) }) { sc ->
                        s.stamp?.layer(sc, upright.width, upright.height)?.toBitmap()
                    }
                }
                val paused = Capture.Shot(upright, 0, s.overlay, s.renderer, s.band, s.meta, null, drawnScale?.first, s.stamp)
                _annotating.value = Annotating(paused, Sketch(), scaleLayer = drawnScale?.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("BebirdSpike", "pausing to annotate failed", e)
                val reason = e.message ?: e.javaClass.simpleName
                _captureResults.tryEmit(Capture.Result.Problem(getApplication<Application>().getString(R.string.annotate_pause_failed, reason)))
            } finally {
                if (pausing === coroutineContext[Job]) pausing = null
            }
        }
    }

    /** Change the marks; not while a save runs. */
    fun editAnnotations(change: (Sketch) -> Sketch) {
        _annotating.update { a -> if (a == null || !AnnotateRules.canEdit(a.progress)) a else Annotating(a.shot, change(a.sketch), a.progress, a.scaleLayer) }
    }

    /** Back to the live view; unsaved marks are dropped. Not while a save runs. */
    fun resumeLive() {
        pausing?.cancel()
        pausing = null
        _annotating.update { a -> if (a != null && a.progress.saving) a else null }
    }

    /**
     * Save the paused frame and its annotated copy. Back to the live view once both are
     * saved; if either fails the frame stays paused with its marks, and Save tries again.
     */
    fun saveAnnotated() {
        val a = _annotating.value ?: return
        if (a.progress.saving) return
        val renderer = _annotationRenderer.value ?: return
        val started = Annotating(a.shot, a.sketch, a.progress.start(), a.scaleLayer)
        _annotating.value = started
        capture.snapshotAnnotated(
            a.shot, a.sketch.marks, renderer, a.progress.savedOriginal, done = { _captureResults.tryEmit(it) },
        ) { outcome ->
            _annotating.update { cur ->
                if (cur !== started) cur else started.progress.finish(outcome)?.let { Annotating(cur.shot, cur.sketch, it, cur.scaleLayer) }
            }
        }
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

    fun toggleRecording() {
        if (_recordingSince.value == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        // never behind a paused view: the recording would carry on unseen
        if (!AnnotateRules.canRecord(_annotating.value != null, pausing != null)) return
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

    /**
     * Finish the recording, if any (also when leaving the app: recording is foreground-only for
     * now). Returns the file's completion, or null if nothing was recording.
     */
    fun stopRecording(): Future<*>? {
        if (_recordingSince.value == null) return null
        _recordingSince.value = null  // the UI stops at once; the file is finished on the worker
        return capture.stopRecording { _captureResults.tryEmit(it) }
    }

    private val quitSequence = QuitSequence(viewModelScope, Dispatchers.IO, ::stopRecording, grace::quit, connection::awaitRelease)
    private val _quitting = MutableStateFlow(false)
    /** Quit is under way: the controls that would start something else are off. */
    val quitting: StateFlow<Boolean> = _quitting.asStateFlow()
    private val _quitDone = MutableStateFlow(false)
    /**
     * Quit has finished: whichever activity is current closes the app. A flow, not a callback,
     * so an activity recreated mid-sequence still does.
     */
    val quitDone: StateFlow<Boolean> = _quitDone.asStateFlow()

    /**
     * The Quit button (#38): finish any recording, then end the connection now, switching the
     * scope off if video had started (no grace period), then [quitDone] once the network is
     * released. Once only. Leaving with Back or a swipe mid-sequence clears this ViewModel and
     * cancels the rest; the activity's onClose then ends the connection per the settings.
     */
    fun quit() {
        if (quitSequence.start { _quitDone.value = true }) _quitting.value = true
    }

    override fun onCleared() {
        Log.i("BebirdSpike", "ViewModel cleared")
        stopRecording()
        capture.close()
        // Not the connection: it belongs to the app and outlives the screen (see GraceKeeper).
    }
}
