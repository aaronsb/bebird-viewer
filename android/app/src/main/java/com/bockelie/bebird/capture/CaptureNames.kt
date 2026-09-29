// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import java.time.LocalDate
import java.time.LocalDateTime

/** File names for captures, as the desktop's: bebird-YYYYMMDD-HHMMSS[_zoomed|_annotated].ext. */
object CaptureNames {
    fun still(time: LocalDateTime, zoomed: Boolean = false) = base(time) + (if (zoomed) "_zoomed" else "") + ".jpg"

    /** The annotated copy of the still [still] would name for the same [time]. */
    fun annotated(time: LocalDateTime) = base(time) + "_annotated.jpg"

    fun video(time: LocalDateTime) = base(time) + ".mp4"

    private fun base(t: LocalDateTime) =
        "bebird-%04d%02d%02d-%02d%02d%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)

    /** Every capture goes under here, stills and videos alike, in a folder per day. */
    const val ROOT = "Pictures/Bebird"

    /** The folder for captures taken on [date] (local): Pictures/Bebird/YYYY-MM-DD. */
    fun folder(date: LocalDate) = "%s/%04d-%02d-%02d".format(ROOT, date.year, date.monthValue, date.dayOfMonth)
}
