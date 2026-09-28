// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import java.time.LocalDateTime

/** File names for captures, as the desktop's: bebird-YYYYMMDD-HHMMSS[_zoomed].ext. */
object CaptureNames {
    fun still(time: LocalDateTime, zoomed: Boolean = false) = base(time) + (if (zoomed) "_zoomed" else "") + ".jpg"

    fun video(time: LocalDateTime) = base(time) + ".mp4"

    private fun base(t: LocalDateTime) =
        "bebird-%04d%02d%02d-%02d%02d%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)

    /** Stills and videos both go here, so one folder holds every capture. */
    const val FOLDER = "Pictures/Bebird"
}
