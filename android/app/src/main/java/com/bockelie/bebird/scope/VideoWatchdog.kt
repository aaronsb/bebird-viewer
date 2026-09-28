// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

/**
 * The timing decisions of viewer.py's receive loop, kept pure so they can be unit tested.
 * Times are milliseconds from any monotonic clock.
 *
 * Before any video arrives, if nothing has come [retryMs] after START, the session may send
 * STOP then START again (the official app does this too). Once a single video datagram has
 * arrived the scope is streaming, decodable or not, and it never asks for another START: a
 * second START while streaming re-initialises the camera, and repeated ones wedge it.
 */
class VideoWatchdog(
    startedAt: Long,
    private val retryMs: Long = RETRY_MS,
    private val stallMs: Long = STALL_MS,
) {
    private var lastStart = startedAt
    private var lastRx = startedAt
    var packets = 0; private set
    var frames = 0; private set
    var retries = 0; private set

    fun onPacket(now: Long) {
        packets++
        lastRx = now
    }

    fun onFrame(now: Long) {
        frames++
        lastRx = now
    }

    /** True when START should be re-sent (after a STOP); records that it was. */
    fun shouldRetryStart(now: Long): Boolean {
        if (packets > 0 || frames > 0 || retries >= MAX_RETRIES || now - lastStart <= retryMs) return false
        retries++
        lastStart = now
        return true
    }

    /** Video had been flowing but nothing has arrived for [stallMs]: scope off or wedged. */
    fun stalled(now: Long): Boolean = frames > 0 && now - lastRx > stallMs

    companion object {
        const val RETRY_MS = 2000L
        const val STALL_MS = 3000L
        // viewer.py retries without limit; the spike stops after a few so a wedged scope shows
        // up as "no video" in the log instead of a stream of STARTs.
        const val MAX_RETRIES = 3
    }
}
