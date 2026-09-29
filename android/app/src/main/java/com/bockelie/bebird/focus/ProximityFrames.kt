// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The frame-path hook: each decoded raw frame is offered here. While the [gate] is disabled
 * nothing happens (no gate call, no luma, no work queued). While enabled the estimator runs on
 * [run] (one worker), never on the caller's thread; a frame that arrives while the previous one
 * is still being estimated is dropped, so decoding never waits. Each result goes to [publish].
 *
 * The overlay is best effort and must never take the viewer down: anything the estimator throws
 * (errors too: a StackOverflowError once did) goes to [onError], null is published, and the
 * gate is recreated (a fresh estimator, as after switching it off and on). If that keeps
 * happening ([GIVE_UP_AFTER] failures within [GIVE_UP_WINDOW_MS]) estimation stops for the rest
 * of the session and [onGiveUp] is called, so a deterministic bug can't fail at 10 fps.
 */
class ProximityFrames(
    val gate: ProximityGate,
    private val run: (Runnable) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val onGiveUp: () -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val publish: (FocusResult?) -> Unit,
) {
    private val busy = AtomicBoolean(false)
    @Volatile var dropped = 0; private set
    @Volatile var errors = 0; private set
    /** Estimation stopped after repeated failures; stays so until the app restarts. */
    @Volatile var gaveUp = false; private set
    private val failures = ArrayDeque<Long>()  // worker only

    /** Offer a frame at [t] s with roll [roll]; [fill] writes its raw luma into the gate's buffers. True if queued. */
    fun offer(t: Double, roll: Int, fill: (LumaBuffers) -> Unit): Boolean {
        if (gaveUp || !gate.enabled) return false
        if (!busy.compareAndSet(false, true)) {
            dropped++
            return false
        }
        try {
            run(Runnable {
                try {
                    publish(gate.onFrame(t, roll, fill))
                } catch (failure: Throwable) {
                    failed(failure)
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

    /** Worker only: a frame failed. Start over, or give up if it keeps failing. */
    private fun failed(failure: Throwable) {
        errors++
        runCatching { onError(failure) }
        publish(null)
        val now = clockMs()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > GIVE_UP_WINDOW_MS) failures.removeFirst()
        // Out of memory (or another VM error, except a stack overflow from one bad frame): a
        // fresh estimator would only allocate again, so stop at once.
        val fatal = failure is VirtualMachineError && failure !is StackOverflowError
        if (fatal || failures.size >= GIVE_UP_AFTER) {
            gaveUp = true
            gate.setEnabled(false)  // drops the estimator and its buffers; the setting is untouched
            runCatching { onGiveUp() }
            return
        }
        restart()  // a fresh estimator, unless it was switched off meanwhile
    }

    /** Start the estimator over (fresh tip mask, peak and arming), if it is running. */
    fun restart() = synchronized(gate) {
        if (gate.enabled) {
            gate.setEnabled(false)
            gate.setEnabled(true)
        }
    }

    companion object {
        const val GIVE_UP_AFTER = 3
        const val GIVE_UP_WINDOW_MS = 10_000L

        /**
         * Whether a decoded frame can go to the estimator: the raw 480 × 480 frame as a software
         * ARGB_8888 bitmap (what BitmapFactory decodes by default). Anything else (a hardware or
         * RGB_565 bitmap, another size) is skipped, not converted.
         */
        fun accepts(width: Int, height: Int, softwareArgb8888: Boolean): Boolean =
            width == FrameGeometry.SIZE && height == FrameGeometry.SIZE && softwareArgb8888
    }
}
