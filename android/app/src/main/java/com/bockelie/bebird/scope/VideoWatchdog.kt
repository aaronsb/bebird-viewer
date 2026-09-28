// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

/**
 * The timing decisions of viewer.py's receive loop, kept pure so they can be unit tested.
 * Times are milliseconds from any monotonic clock.
 *
 * Before the first frame, if nothing has arrived [RETRY_MS] after START, the session may send
 * STOP then START again (the official app does this too). Once a frame has arrived it never
 * asks for another START: a second START while streaming re-initialises the camera, and
 * repeated ones wedge it.
 */
class VideoWatchdog(startedAt: Long) {
    private var lastStart = startedAt
    private var lastRx = startedAt
    var frames = 0; private set
    var retries = 0; private set

    fun onFrame(now: Long) {
        frames++
        lastRx = now
    }

    fun onPacket(now: Long) {
        lastRx = now
    }

    /** True when START should be re-sent (after a STOP); records that it was. */
    fun shouldRetryStart(now: Long): Boolean {
        if (frames > 0 || retries >= MAX_RETRIES || now - lastStart <= RETRY_MS) return false
        retries++
        lastStart = now
        return true
    }

    /** Video had been flowing but nothing has arrived for [STALL_MS]: scope off or wedged. */
    fun stalled(now: Long): Boolean = frames > 0 && now - lastRx > STALL_MS

    companion object {
        const val RETRY_MS = 2000L
        const val STALL_MS = 3000L
        // viewer.py retries without limit; the spike stops after a few so a wedged scope shows
        // up as "no video" in the log instead of a stream of STARTs.
        const val MAX_RETRIES = 3
    }
}
