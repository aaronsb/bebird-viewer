// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The frame-path hook: each decoded raw frame is offered here. While the [gate] is disabled
 * nothing happens (no gate call, no luma, no work queued). While enabled the estimator runs on
 * [run] (one worker), never on the caller's thread; a frame that arrives while the previous one
 * is still being estimated is dropped, so decoding never waits. Each result goes to [publish].
 *
 * The overlay is best effort and must never take the viewer down: if the estimator throws, the
 * error goes to [onError], null is published, and the gate is recreated (a fresh estimator, as
 * after switching it off and on), so a bad frame costs one result, not the app.
 */
class ProximityFrames(
    val gate: ProximityGate,
    private val run: (Runnable) -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val publish: (FocusResult?) -> Unit,
) {
    private val busy = AtomicBoolean(false)
    @Volatile var dropped = 0; private set
    @Volatile var errors = 0; private set

    /** Offer a frame at [t] s with roll [roll]; [fill] writes its raw luma into the gate's buffers. True if queued. */
    fun offer(t: Double, roll: Int, fill: (LumaBuffers) -> Unit): Boolean {
        if (!gate.enabled) return false
        if (!busy.compareAndSet(false, true)) {
            dropped++
            return false
        }
        try {
            run(Runnable {
                try {
                    publish(gate.onFrame(t, roll, fill))
                } catch (e: Exception) {
                    errors++
                    onError(e)
                    publish(null)
                    // start over with a fresh estimator, unless it was switched off meanwhile
                    synchronized(gate) {
                        if (gate.enabled) {
                            gate.setEnabled(false)
                            gate.setEnabled(true)
                        }
                    }
                } finally {
                    busy.set(false)
                }
            })
        } catch (e: RuntimeException) {  // the worker is gone (shut down)
            busy.set(false)
            throw e
        }
        return true
    }

    /** Start the estimator over (fresh tip mask, peak and arming), if it is running. */
    fun restart() = synchronized(gate) {
        if (gate.enabled) {
            gate.setEnabled(false)
            gate.setEnabled(true)
        }
    }

    companion object {
        /**
         * Whether a decoded frame can go to the estimator: the raw 480 × 480 frame as a software
         * ARGB_8888 bitmap (what BitmapFactory decodes by default). Anything else (a hardware or
         * RGB_565 bitmap, another size) is skipped, not converted.
         */
        fun accepts(width: Int, height: Int, softwareArgb8888: Boolean): Boolean =
            width == FrameGeometry.SIZE && height == FrameGeometry.SIZE && softwareArgb8888
    }
}
