// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.settings.KeyValue

/** What the per-frame hook needs from an estimator; [FocusEstimator] is the real one. */
fun interface FrameEstimator {
    fun update(luma: ByteArray, t: Double, roll: Int): FocusResult
}

/**
 * The master switch for proximity estimation, held by whoever sees the frames. While disabled
 * there is no estimator and no luma buffer: [onFrame] returns null without calling its luma
 * callback, so the frame path does no conversion, no mask learning, no metrics and no
 * allocation. Enabling creates a fresh estimator (warm-up, empty tip mask, no peak); disabling
 * drops it with everything it learned. The user's overlay choices live in
 * [ProximitySettings] and are untouched by either.
 *
 * [setEnabled] may be called from any thread; [onFrame] from the one frame thread.
 */
class ProximityGate(private val create: () -> FrameEstimator = { FocusEstimator() }) {
    @PublishedApi internal class Active(val estimator: FrameEstimator) {
        val luma = ByteArray(FrameGeometry.SIZE * FrameGeometry.SIZE)
    }

    @Volatile @PublishedApi internal var active: Active? = null

    val enabled: Boolean get() = active != null

    fun setEnabled(on: Boolean) {
        synchronized(this) {
            if (!on) active = null
            else if (active == null) active = Active(create())
        }
    }

    /**
     * One frame: when enabled, [fillLuma] writes the raw, unrotated frame's luma into the buffer
     * it is given (e.g. [android.graphics.Bitmap.lumaInto]) and the estimator runs; when
     * disabled, nothing runs and the result is null (no scale, no CLOSE).
     */
    inline fun onFrame(t: Double, roll: Int, fillLuma: (ByteArray) -> Unit): FocusResult? {
        val a = active ?: return null
        fillLuma(a.luma)
        return a.estimator.update(a.luma, t, roll)
    }
}

/**
 * The proximity settings, persisted in the app's settings store next to the others. Each is
 * independent: turning estimation off and on again keeps the scale style and the CLOSE choice.
 */
class ProximitySettings(private val kv: KeyValue) {
    /** Master switch: whether the estimator runs at all. On by default (validated in use). */
    var enabled: Boolean
        get() = kv.getBoolean("proximity", true)
        set(v) = kv.putBoolean("proximity", v)
    /** Which scale to draw while estimating; [ScaleStyle.NONE] hides it. */
    var scaleStyle: ScaleStyle
        get() = ScaleStyle.entries.firstOrNull { it.name == kv.getString("proximity_scale", "") } ?: ScaleStyle.RING
        set(v) = kv.putString("proximity_scale", v.name)
    /** Whether the CLOSE indicator is shown. */
    var closeIndicator: Boolean
        get() = kv.getBoolean("proximity_close", true)
        set(v) = kv.putBoolean("proximity_close", v)
}
