// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The frame-path hook: each decoded raw frame is offered here. While the [gate] is disabled
 * nothing happens (no gate call, no luma, no work queued). While enabled the estimator runs on
 * [run] (one worker), never on the caller's thread; a frame that arrives while the previous one
 * is still being estimated is dropped, so decoding never waits. Each result goes to [publish].
 */
class ProximityFrames(
    val gate: ProximityGate,
    private val run: (Runnable) -> Unit,
    private val publish: (FocusResult?) -> Unit,
) {
    private val busy = AtomicBoolean(false)
    @Volatile var dropped = 0; private set

    /** Offer a frame at [t] s with roll [roll]; [fillLuma] writes its raw luma. True if queued. */
    fun offer(t: Double, roll: Int, fillLuma: (ByteArray) -> Unit): Boolean {
        if (!gate.enabled) return false
        if (!busy.compareAndSet(false, true)) {
            dropped++
            return false
        }
        try {
            run(Runnable {
                try {
                    publish(gate.onFrame(t, roll, fillLuma))
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
}
