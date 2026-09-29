// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import java.time.LocalDate
import java.time.LocalDateTime

/** File names for captures, as the desktop's: bebird-YYYYMMDD-HHMMSS[_zoomed|_annotated].ext. */
object CaptureNames {
    fun still(time: LocalDateTime) = base(time) + ".jpg"

    /**
     * The zoomed crop's name for a still saved as [still]: the same base name, whatever
     * MediaStore made of it ("bebird-… (1).jpg" when another still took the name that second).
     */
    fun zoomed(still: String) = still.removeSuffix(".jpg") + "_zoomed.jpg"

    /**
     * The annotated copy's name for a still saved as [still]: the same base name, whatever
     * MediaStore made of it ("bebird-… (1).jpg" when a snapshot took the name that second).
     */
    fun annotated(still: String) = still.removeSuffix(".jpg") + "_annotated.jpg"

    fun video(time: LocalDateTime) = base(time) + ".mp4"

    private fun base(t: LocalDateTime) =
        "bebird-%04d%02d%02d-%02d%02d%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)

    /** Every capture goes under here, stills and videos alike, in a folder per day. */
    const val ROOT = "Pictures/Bebird"

    /** The folder for captures taken on [date] (local): Pictures/Bebird/YYYY-MM-DD. */
    fun folder(date: LocalDate) = "%s/%04d-%02d-%02d".format(ROOT, date.year, date.monthValue, date.dayOfMonth)
}
