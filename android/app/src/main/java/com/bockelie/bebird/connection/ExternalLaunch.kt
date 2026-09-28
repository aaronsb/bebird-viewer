// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

/**
 * The app opening another app over itself (the file browser, a viewer for a capture): that
 * isn't leaving, so [GraceKeeper] keeps the connection through the stop that follows for at
 * least [capMs], even with no grace period. A stop more than [capMs] after the launch is an
 * ordinary one. Pure; the keeper feeds it a monotonic clock.
 */
class ExternalLaunch(val capMs: Long = 2 * 60_000L) {
    private var since: Long? = null

    /** Just before starting another activity over ours. */
    fun begin(now: Long) {
        since = now
    }

    /** Back in our activity, the launch failed, or its stop has been handled. */
    fun returned() {
        since = null
    }

    /** At onStop: true if this stop is our own launch, so the connection should stay up. */
    fun coversStop(now: Long): Boolean = since.let { it != null && now - it < capMs }
}
