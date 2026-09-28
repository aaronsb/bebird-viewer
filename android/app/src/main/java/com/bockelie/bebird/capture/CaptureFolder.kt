// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import java.time.LocalDate

/**
 * Browsing captures with the system document picker (no browser in the app): it opens in
 * today's folder if there is one, else Pictures/Bebird, and the picked file is then shown with
 * the phone's default app. Pure: the caller does the MediaStore lookups and the launching.
 * (A plain VIEW of the folder isn't reliable: on a Pixel nothing handles it.)
 */
object CaptureFolder {
    const val AUTHORITY = "com.android.externalstorage.documents"
    /** What the picker lists: our captures are JPEG stills and MP4 videos. */
    val MIME_TYPES = arrayOf("image/*", "video/*")

    sealed interface Plan {
        /** Open the picker at [initialUri] (a document uri, see [documentUri]). */
        data class Pick(val initialUri: String) : Plan
        /** No capture has been saved yet, so there is nothing to browse. */
        data object Empty : Plan
    }

    /** DocumentsContract.buildDocumentUri(AUTHORITY, "primary:[dir]"). */
    fun documentUri(dir: String) = "content://$AUTHORITY/document/${encode("primary:$dir")}"

    /**
     * Where to open: nothing if there are no captures at all; today's folder ([today]) if it
     * has any ([todayHasCaptures] is asked only then); else the Pictures/Bebird root.
     */
    fun plan(anyCaptures: Boolean, today: LocalDate, todayHasCaptures: (String) -> Boolean): Plan {
        if (!anyCaptures) return Plan.Empty
        val day = CaptureNames.folder(today)
        return Plan.Pick(documentUri(if (todayHasCaptures(day)) day else CaptureNames.ROOT))
    }

    /** Uri.encode: everything but letters, digits and -_.!~*'() as UTF-8 %XX. */
    private fun encode(s: String) = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}
