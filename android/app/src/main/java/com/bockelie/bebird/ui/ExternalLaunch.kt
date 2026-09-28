// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

/**
 * The app opening another app over itself (the file browser, a viewer for a capture): that
 * isn't leaving, so the scope connection stays up while it is on top, for at most [capMs]. If
 * the user goes elsewhere from there and doesn't come back, the connection is let go as on
 * leaving. Pure; the activity feeds it a monotonic clock. (#18's keeper can take this over.)
 */
class ExternalLaunch(private val capMs: Long = 2 * 60_000L) {
    private var since: Long? = null

    /** Just before starting another activity over ours. */
    fun begin(now: Long) {
        since = now
    }

    /** Back in our activity (or the launch failed): an ordinary stop disconnects again. */
    fun returned() {
        since = null
    }

    /** At onStop: true if this stop is our own launch, so the connection should stay up. */
    fun coversStop(now: Long): Boolean = since.let { it != null && now - it < capMs }

    /** The cap has run out while covered: time to disconnect. */
    fun expired(now: Long): Boolean = since.let { it != null && now - it >= capMs }

    /** When the cap runs out, or null if nothing is covering the app. */
    fun deadline(): Long? = since?.plus(capMs)
}
